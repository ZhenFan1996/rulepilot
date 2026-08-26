package com.rulepilot.teaching.adapter.out.vision;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.document.DocumentPageImages;
import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionProposer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Runs a bounded local OpenCV process that owns pixel geometry but never emits recognized rulebook prose. */
@Component
public final class OpenCvVisualRegionProposer implements VisualRegionProposer {

    private static final String SCRIPT_RESOURCE = "visual/opencv-region-proposals.py";
    private static final int MAX_RESPONSE_BYTES = 64 * 1_024;
    private static final int MAX_INPUT_BYTES = 5 * 1_024 * 1_024;
    private static final long MAX_PAGE_PIXELS = 12_000_000L;
    private static final long PROCESS_WORKING_ADDRESS_SPACE_BYTES = 384L * 1_024 * 1_024;
    private static final Duration MAX_TOOL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TERMINATION_PHASE_WAIT = Duration.ofMillis(50);
    private static final Duration TERMINATION_WAIT = Duration.ofMillis(250);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ObservationRegistry observations;
    private final boolean enabled;
    private final String pythonCommand;
    private final Duration configuredTimeout;
    private final int maxRegions;
    private volatile Path extractedScript;

    public OpenCvVisualRegionProposer(
            ObservationRegistry observations,
            @Value("${rulepilot.visual.region-proposal.enabled:true}") boolean enabled,
            @Value("${rulepilot.visual.region-proposal.python-command:python3}") String pythonCommand,
            @Value("${rulepilot.visual.region-proposal.timeout:PT2S}") Duration configuredTimeout,
            @Value("${rulepilot.visual.region-proposal.max-regions-per-page:32}") int maxRegions) {
        if (observations == null
                || pythonCommand == null
                || pythonCommand.isBlank()
                || configuredTimeout == null
                || configuredTimeout.isZero()
                || configuredTimeout.isNegative()
                || configuredTimeout.compareTo(MAX_TOOL_TIMEOUT) > 0
                || maxRegions < 1
                || maxRegions > 64) {
            throw new IllegalArgumentException("OpenCV visual region proposer configuration is invalid");
        }
        this.observations = observations;
        this.enabled = enabled;
        this.pythonCommand = pythonCommand.strip();
        this.configuredTimeout = configuredTimeout;
        this.maxRegions = maxRegions;
    }

    @Override
    public ProposalResult propose(DocumentPageImages.PageImage page, Duration timeout) {
        if (page == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("visual region proposal request is invalid");
        }
        Observation observation = Observation.createNotStarted("rulepilot.visual.region_proposal", observations)
                .contextualName("visual-region-proposal")
                .lowCardinalityKeyValue("tool", "opencv")
                .start();
        ProposalResult result = ProposalResult.failed();
        try (Observation.Scope ignored = observation.openScope()) {
            result = proposeObserved(page, shorter(timeout, configuredTimeout));
            return result;
        } finally {
            observation.lowCardinalityKeyValue(
                    "outcome", result.diagnostic().name().toLowerCase(java.util.Locale.ROOT));
            observation.stop();
        }
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    private ProposalResult proposeObserved(DocumentPageImages.PageImage page, Duration timeout) {
        if (!enabled) return ProposalResult.unavailable();
        byte[] pageContent = page.content();
        if (pageContent.length > MAX_INPUT_BYTES || (long) page.width() * page.height() > MAX_PAGE_PIXELS) {
            return ProposalResult.failed();
        }
        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonCommand,
                    script().toString(),
                    "--max-regions",
                    Integer.toString(maxRegions),
                    "--page-width",
                    Integer.toString(page.width()),
                    "--page-height",
                    Integer.toString(page.height()));
            processBuilder.environment().put(
                    "RULEPILOT_OPENCV_WORKING_ADDRESS_SPACE_BYTES",
                    Long.toString(PROCESS_WORKING_ADDRESS_SPACE_BYTES));
            processBuilder.environment().put(
                    "RULEPILOT_OPENCV_CPU_LIMIT_SECONDS", Integer.toString(cpuLimitSeconds(timeout)));
            process = processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException unavailable) {
            return ProposalResult.unavailable();
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        OutputStream input = process.getOutputStream();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        Thread inputWriter = Thread.ofVirtual().name("opencv-region-proposal-input").start(() -> {
            try (input) {
                input.write(pageContent);
            } catch (IOException failure) {
                writeFailure.set(failure);
            }
        });
        try {
            if (!process.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                terminate(process, input, inputWriter);
                return ProposalResult.timeout();
            }
            inputWriter.join(remainingMillis(deadlineNanos));
            if (inputWriter.isAlive()) {
                terminate(process, input, inputWriter);
                return ProposalResult.timeout();
            }
            if (writeFailure.get() != null) return ProposalResult.unavailable();
            byte[] response;
            try (InputStream output = process.getInputStream()) {
                response = output.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (process.exitValue() != 0 || response.length == 0 || response.length > MAX_RESPONSE_BYTES) {
                return ProposalResult.unavailable();
            }
            ParsedResponse parsed = parseResponse(response, page.width(), page.height(), maxRegions);
            return parsed.runtimeContractFailure() ? ProposalResult.unavailable() : parsed.result();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminate(process, input, inputWriter);
            return ProposalResult.failed();
        } catch (IOException invalidResponse) {
            return ProposalResult.unavailable();
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static int cpuLimitSeconds(Duration timeout) {
        long wholeSeconds = Math.max(1L, (timeout.toMillis() + 999L) / 1_000L);
        return Math.toIntExact(Math.min(MAX_TOOL_TIMEOUT.toSeconds() + 1L, wholeSeconds + 1L));
    }

    private static void terminate(Process process, OutputStream input, Thread inputWriter) {
        boolean interrupted = Thread.interrupted();
        try {
            long deadlineNanos = System.nanoTime() + TERMINATION_WAIT.toNanos();
            ProcessHandle parent = process.toHandle();

            // Capture and terminate descendants while the parent still owns them. Killing the parent first can
            // re-parent native helpers and make them invisible to ProcessHandle.descendants().
            terminateDescendants(parent, deadlineNanos);
            if (parent.isAlive()) parent.destroy();
            awaitExit(List.of(parent), deadlineNanos);
            if (parent.isAlive()) parent.destroyForcibly();
            awaitExit(List.of(parent), deadlineNanos);

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos > 0 && inputWriter.isAlive()) {
                inputWriter.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            }
            if (inputWriter.isAlive()) inputWriter.interrupt();
            if (!inputWriter.isAlive()) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // The writer normally owns this close; the process tree is already terminated here.
                }
            }
        } catch (InterruptedException terminationInterrupted) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void terminateDescendants(ProcessHandle parent, long deadlineNanos) throws InterruptedException {
        List<ProcessHandle> descendants = parent.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        awaitExit(descendants, phaseDeadline(deadlineNanos));
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        awaitExit(descendants, phaseDeadline(deadlineNanos));

        // A helper can fork while the first snapshot is being stopped. Take one final snapshot before terminating
        // the parent so newly-created descendants cannot escape through re-parenting.
        List<ProcessHandle> lateDescendants = parent.descendants().filter(ProcessHandle::isAlive).toList();
        lateDescendants.forEach(ProcessHandle::destroyForcibly);
        awaitExit(lateDescendants, phaseDeadline(deadlineNanos));
    }

    private static long phaseDeadline(long deadlineNanos) {
        return Math.min(deadlineNanos, System.nanoTime() + TERMINATION_PHASE_WAIT.toNanos());
    }

    private static void awaitExit(List<ProcessHandle> handles, long deadlineNanos) throws InterruptedException {
        while (handles.stream().anyMatch(ProcessHandle::isAlive)) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }

    private synchronized Path script() throws IOException {
        if (extractedScript != null && Files.isRegularFile(extractedScript)) return extractedScript;
        ClassPathResource resource = new ClassPathResource(SCRIPT_RESOURCE);
        Path target = Files.createTempFile("rulepilot-opencv-region-proposals-", ".py");
        try (InputStream source = resource.getInputStream()) {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        extractedScript = target;
        return target;
    }

    static ProposalResult parse(byte[] response, int expectedWidth, int expectedHeight, int maxRegions)
            throws IOException {
        return parseResponse(response, expectedWidth, expectedHeight, maxRegions).result();
    }

    private static ParsedResponse parseResponse(
            byte[] response, int expectedWidth, int expectedHeight, int maxRegions) throws IOException {
        JsonNode root = JSON.readTree(response);
        if (root == null
                || !root.isObject()
                || !exactFields(root, Set.of("schemaVersion", "width", "height", "regions"))
                || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != 1
                || !root.path("width").isInt()
                || !root.path("height").isInt()
                || !root.path("regions").isArray()
                || root.path("regions").size() > maxRegions) {
            return ParsedResponse.runtimeFailure();
        }
        if (root.path("width").intValue() != expectedWidth
                || root.path("height").intValue() != expectedHeight) {
            return ParsedResponse.pageFailure();
        }
        List<Proposal> proposals = new ArrayList<>();
        Set<Rectangle> unique = new HashSet<>();
        for (JsonNode region : root.path("regions")) {
            if (!region.isObject()
                    || !exactFields(region, Set.of("x", "y", "width", "height"))
                    || !integral(region, "x")
                    || !integral(region, "y")
                    || !integral(region, "width")
                    || !integral(region, "height")) {
                return ParsedResponse.runtimeFailure();
            }
            int x = region.path("x").intValue();
            int y = region.path("y").intValue();
            int width = region.path("width").intValue();
            int height = region.path("height").intValue();
            if (x < 0
                    || y < 0
                    || width < 1
                    || height < 1
                    || (long) x + width > expectedWidth
                    || (long) y + height > expectedHeight) {
                return ParsedResponse.pageFailure();
            }
            Rectangle normalized = normalize(x, y, width, height, expectedWidth, expectedHeight);
            if (normalized.width() >= 20
                    && normalized.height() >= 20
                    && !(normalized.x() == 0
                            && normalized.y() == 0
                            && normalized.width() == 1_000
                            && normalized.height() == 1_000)
                    && unique.add(normalized)) {
                proposals.add(new Proposal(normalized));
            }
        }
        return ParsedResponse.success(
                proposals.isEmpty() ? ProposalResult.none() : ProposalResult.found(proposals));
    }

    private static Rectangle normalize(
            int x, int y, int width, int height, int pageWidth, int pageHeight) {
        int left = Math.clamp((int) Math.floor(x * 1_000.0 / pageWidth), 0, 999);
        int top = Math.clamp((int) Math.floor(y * 1_000.0 / pageHeight), 0, 999);
        int right = Math.clamp((int) Math.ceil((x + width) * 1_000.0 / pageWidth), left + 1, 1_000);
        int bottom = Math.clamp((int) Math.ceil((y + height) * 1_000.0 / pageHeight), top + 1, 1_000);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private static boolean exactFields(JsonNode object, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static boolean integral(JsonNode object, String name) {
        return object.has(name)
                && object.get(name).isIntegralNumber()
                && object.get(name).canConvertToInt();
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private record ParsedResponse(ProposalResult result, boolean runtimeContractFailure) {
        private ParsedResponse {
            if (result == null) throw new IllegalArgumentException("parsed proposal result is required");
        }

        private static ParsedResponse success(ProposalResult result) {
            return new ParsedResponse(result, false);
        }

        private static ParsedResponse pageFailure() {
            return new ParsedResponse(ProposalResult.failed(), false);
        }

        private static ParsedResponse runtimeFailure() {
            return new ParsedResponse(ProposalResult.failed(), true);
        }
    }
}
