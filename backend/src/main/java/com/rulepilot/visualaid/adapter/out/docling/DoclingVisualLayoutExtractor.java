package com.rulepilot.visualaid.adapter.out.docling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.visualaid.VisualRegionCatalog.Region;
import com.rulepilot.visualaid.application.VisualLayoutExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Streaming IBM Docling adapter. It returns typed geometry only and never exposes OCR prose to routing. */
public final class DoclingVisualLayoutExtractor implements VisualLayoutExtractor {

    private static final MediaType PDF = MediaType.get("application/pdf");
    private static final int MAX_REGIONS_PER_PAGE = 64;
    private static final Set<String> TERMINAL_FAILURES = Set.of("failure", "failed", "cancelled", "canceled");

    private final DoclingVisualLayoutProperties properties;
    private final OkHttpClient http;
    private final ObjectMapper json;

    DoclingVisualLayoutExtractor(
            DoclingVisualLayoutProperties properties,
            OkHttpClient http,
            ObjectMapper json) {
        this.properties = properties;
        this.http = http;
        this.json = json;
    }

    @Override
    public Extraction extract(InputStream rulebookPdf) {
        if (rulebookPdf == null) throw new IllegalArgumentException("rulebook PDF is required");
        Path temporaryPdf = null;
        try {
            temporaryPdf = Files.createTempFile("rulepilot-docling-", ".pdf");
            copyBounded(rulebookPdf, temporaryPdf);
            String taskId = submit(temporaryPdf);
            await(taskId);
            return mapDocument(downloadResult(taskId));
        } catch (IOException exception) {
            throw new UncheckedIOException("Docling visual layout request failed", exception);
        } finally {
            if (temporaryPdf != null) {
                try {
                    Files.deleteIfExists(temporaryPdf);
                } catch (IOException ignored) {
                    // The OS temporary directory remains the recovery boundary for an exceptional cleanup failure.
                }
            }
        }
    }

    private void copyBounded(InputStream source, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (OutputStream output = Files.newOutputStream(target)) {
            for (int read; (read = source.read(buffer)) != -1; ) {
                total += read;
                if (total > properties.maxFileBytes()) {
                    throw new IllegalArgumentException("rulebook exceeds the Docling upload limit");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) throw new IllegalArgumentException("rulebook PDF is empty");
    }

    private String submit(Path pdf) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", "rulebook.pdf", RequestBody.create(pdf.toFile(), PDF))
                .addFormDataPart("to_formats", "json")
                .addFormDataPart("target_type", "presigned_url")
                .build();
        JsonNode response = executeJson(new Request.Builder()
                .url(properties.serviceUrl() + "/v1/convert/file/async")
                .header("X-Api-Key", properties.apiKey())
                .post(body)
                .build());
        String taskId = response.path("task_id").asText("").strip();
        if (taskId.isBlank()) throw new IllegalStateException("Docling did not return a task id");
        return taskId;
    }

    private void await(String taskId) throws IOException {
        Instant deadline = Instant.now().plus(properties.timeout());
        while (Instant.now().isBefore(deadline)) {
            JsonNode response = executeJson(authenticatedGet("/v1/status/poll/" + taskId));
            String status = response.path("task_status").asText("").toLowerCase(Locale.ROOT);
            if ("success".equals(status)) return;
            if (TERMINAL_FAILURES.contains(status)) {
                throw new IllegalStateException("Docling conversion failed with status " + status);
            }
            try {
                Thread.sleep(properties.pollInterval().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Docling conversion was interrupted", interrupted);
            }
        }
        throw new IllegalStateException("Docling conversion timed out");
    }

    private JsonNode downloadResult(String taskId) throws IOException {
        JsonNode result = executeJson(authenticatedGet("/v1/result/" + taskId));
        if (result.path("num_succeeded").asInt(0) != 1 || result.path("num_failed").asInt(0) != 0) {
            throw new IllegalStateException("Docling conversion result was not successful");
        }
        JsonNode documents = result.path("documents");
        if (!documents.isArray() || documents.size() != 1) {
            throw new IllegalStateException("Docling returned an unexpected document count");
        }
        for (JsonNode artifact : documents.get(0).path("artifacts")) {
            if ("json".equals(artifact.path("artifact_type").asText())
                    && "application/json".equals(artifact.path("mime_type").asText())) {
                URI uri = allowedArtifactUri(artifact.path("uri").asText());
                return executeJson(new Request.Builder().url(uri.toString()).get().build());
            }
        }
        throw new IllegalStateException("Docling returned no JSON artifact");
    }

    private Request authenticatedGet(String path) {
        return new Request.Builder()
                .url(properties.serviceUrl() + path)
                .header("X-Api-Key", properties.apiKey())
                .get()
                .build();
    }

    private JsonNode executeJson(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Docling request failed with status " + response.code());
            }
            byte[] body = response.body().byteStream().readNBytes(properties.maxResultBytes() + 1);
            if (body.length > properties.maxResultBytes()) {
                throw new IllegalStateException("Docling response exceeds the configured limit");
            }
            return json.readTree(body);
        }
    }

    static Extraction mapDocument(JsonNode document) {
        Map<Integer, PageSize> pages = pageSizes(document.path("pages"));
        if (pages.isEmpty()) throw new IllegalStateException("Docling returned no page dimensions");
        LinkedHashSet<Region> regions = new LinkedHashSet<>();
        Map<Integer, Integer> pageCounts = new HashMap<>();
        addRegions(document.path("pictures"), "PICTURE", pages, pageCounts, regions);
        addRegions(document.path("tables"), "TABLE", pages, pageCounts, regions);
        return new Extraction("docling:ibm-managed", pages.size(), List.copyOf(regions));
    }

    private static Map<Integer, PageSize> pageSizes(JsonNode pagesNode) {
        Map<Integer, PageSize> pages = new HashMap<>();
        if (!pagesNode.isObject()) return pages;
        pagesNode.forEach(page -> {
            int pageNumber = page.path("page_no").asInt(0);
            double width = page.path("size").path("width").asDouble(Double.NaN);
            double height = page.path("size").path("height").asDouble(Double.NaN);
            if (pageNumber > 0 && finitePositive(width) && finitePositive(height)) {
                pages.putIfAbsent(pageNumber, new PageSize(width, height));
            }
        });
        return Map.copyOf(pages);
    }

    private static void addRegions(
            JsonNode items,
            String kind,
            Map<Integer, PageSize> pages,
            Map<Integer, Integer> pageCounts,
            LinkedHashSet<Region> result) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            JsonNode provenance = item.path("prov");
            if (!provenance.isArray()) continue;
            for (JsonNode source : provenance) {
                int pageNumber = source.path("page_no").asInt(0);
                if (pageCounts.getOrDefault(pageNumber, 0) >= MAX_REGIONS_PER_PAGE) continue;
                Region region = normalizedRegion(pageNumber, kind, pages.get(pageNumber), source.path("bbox"));
                if (region != null && result.add(region)) {
                    pageCounts.merge(pageNumber, 1, Integer::sum);
                }
            }
        }
    }

    private static Region normalizedRegion(int pageNumber, String kind, PageSize page, JsonNode bbox) {
        if (pageNumber < 1 || page == null || !bbox.isObject()) return null;
        double left = bbox.path("l").asDouble(Double.NaN);
        double right = bbox.path("r").asDouble(Double.NaN);
        double top = bbox.path("t").asDouble(Double.NaN);
        double bottom = bbox.path("b").asDouble(Double.NaN);
        if (!finite(left, right, top, bottom) || left < 0 || right <= left || right > page.width()) return null;
        String origin = bbox.path("coord_origin").asText("");
        double topFromTop;
        double bottomFromTop;
        if ("BOTTOMLEFT".equals(origin)) {
            if (bottom < 0 || top <= bottom || top > page.height()) return null;
            topFromTop = page.height() - top;
            bottomFromTop = page.height() - bottom;
        } else if ("TOPLEFT".equals(origin)) {
            if (top < 0 || bottom <= top || bottom > page.height()) return null;
            topFromTop = top;
            bottomFromTop = bottom;
        } else {
            return null;
        }
        int x = clamp((int) Math.floor(left * 1_000 / page.width()));
        int y = clamp((int) Math.floor(topFromTop * 1_000 / page.height()));
        int rightNormalized = clamp((int) Math.ceil(right * 1_000 / page.width()));
        int bottomNormalized = clamp((int) Math.ceil(bottomFromTop * 1_000 / page.height()));
        int width = rightNormalized - x;
        int height = bottomNormalized - y;
        if (width < 20 || height < 20 || (x == 0 && y == 0 && width == 1_000 && height == 1_000)) return null;
        return new Region(pageNumber, kind, x, y, width, height);
    }

    private static URI allowedArtifactUri(String raw) {
        URI uri = URI.create(raw);
        String host = uri.getHost();
        boolean amazonS3 = host != null
                && (host.matches("s3\\.[a-z0-9-]+\\.amazonaws\\.com")
                        || host.matches("[a-z0-9.-]+\\.s3\\.[a-z0-9-]+\\.amazonaws\\.com"));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !amazonS3
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalStateException("Docling returned an untrusted artifact URI");
        }
        return uri;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1_000, value));
    }

    private record PageSize(double width, double height) {}
}
