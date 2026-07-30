package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewRequest;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Bounded vision pass that turns page images into an auditable, page-numbered planning catalog. */
@Component
@Primary
public class SpringAiVisualRulebookPageCatalogModel implements VisualRulebookPageCatalogModel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final String systemPrompt;
    private final String iconLocalizationPrompt;
    private final String iconCropReviewPrompt;
    private final int maxCompletionTokens;

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models,
            FakeVisualRulebookPageCatalogModel fake,
            @Value("classpath:prompts/visual-page-catalog-v2-icon-inventory-system.txt") Resource systemPrompt,
            @Value("classpath:prompts/visual-icon-localization-v2-system.txt") Resource iconLocalizationPrompt,
            @Value("classpath:prompts/visual-icon-crop-review-v1-system.txt") Resource iconCropReviewPrompt,
            @Value("${rulepilot.visual.catalog-max-output-tokens:4800}") int maxCompletionTokens)
            throws IOException {
        this.models = models;
        this.fake = fake;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.iconLocalizationPrompt = iconLocalizationPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.iconCropReviewPrompt = iconCropReviewPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        if (this.systemPrompt.isBlank() || this.iconLocalizationPrompt.isBlank() || this.iconCropReviewPrompt.isBlank()) {
            throw new IllegalArgumentException("visual page catalog prompts must not be blank");
        }
        if (maxCompletionTokens < 800 || maxCompletionTokens > 8_000) {
            throw new IllegalArgumentException("visual page catalog output budget is invalid");
        }
        this.maxCompletionTokens = maxCompletionTokens;
    }

    @Override
    public boolean available(String owner) {
        return !models.usesFake(Role.VISUAL, owner) && models.supportsVision(Role.VISUAL, owner);
    }

    @Override
    public CatalogDraft summarize(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return fake.summarize(request);
        }
        RuntimeException firstFailure;
        try {
            return normalizePageBindings(request, summarizeOnce(request, owner, ""));
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        try {
            return normalizePageBindings(request, summarizeOnce(request, owner, """
                    The first catalog was invalid. Return one summary for every supplied page, use only supplied page
                    numbers, preserve visible original-language terms, and return structured data only.
                    """));
        } catch (RuntimeException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private CatalogDraft summarizeOnce(CatalogRequest request, String owner, String correction) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(OpenAiChatOptions.builder()
                    .model(models.modelNameFor(Role.VISUAL, owner))
                    .maxTokens(maxCompletionTokens)
                    .extraBody(Map.of("enable_thinking", false))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()));
        }
        String content = prompt.system(systemPrompt)
                .user(user -> {
                    user.text("""
                                    Attached rulebook page numbers: {pageNumbers}
                                    Rulebook title: {rulebookTitle}
                                    Attachment order: {attachmentOrder}
                                    {viewportTask}
                                    {crossPageTask}
                                    If an image is credits, storage or assembly instructions, or an advertisement for
                                    another named game, say explicitly in factualSummary that it is non-gameplay
                                    material for this rulebook. Do not treat it as a turn, scoring, or end-game rule.
                                    {correction}
                                    Return a JSON object with a pages array. Each array item must have pageNumber,
                                    printedTerms, factualSummary, keywords, visualAnchors, iconOccurrences, and
                                    iconInventoryComplete.
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
                            .param("rulebookTitle", request.rulebookTitle() == null
                                    ? "not supplied; use only what is visible on each page"
                                    : request.rulebookTitle())
                            .param("attachmentOrder", java.util.stream.IntStream.range(0, request.pages().size())
                                    .mapToObj(index -> "image " + (index + 1) + " = PDF page "
                                            + request.pages().get(index).pageNumber())
                                    .collect(java.util.stream.Collectors.joining("; ")))
                            .param("viewportTask", request.viewport() == null
                                    ? "Each attachment is the complete rendered PDF page."
                                    : "The attachment is only a bounded tile of PDF page "
                                            + request.viewport().pageNumber()
                                            + ". Inspect the complete attached tile, not unseen parts of the PDF page. "
                                            + "All returned x, y, width, and height coordinates must use a top-left "
                                            + "0-1000 grid relative to this attached tile. iconInventoryComplete means "
                                            + "that every distinct gameplay icon in this tile was recorded.")
                            .param("crossPageTask", request.pages().size() > 1
                                    ? "These images are a deliberate labeled-reference and operational-rule pair. "
                                            + "Your primary task is to compare repeated icon artwork across both "
                                            + "images, copy the exact component label printed beside the matching "
                                            + "icon, and reconcile every quantity and worked total before writing "
                                            + "either page summary. Never carry a color, emoji, or guessed name into "
                                            + "the final summaries."
                                    : "Inspect this page without guessing the identity of an unlabeled icon.")
                            .param("correction", correction);
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        return parseCatalog(content);
    }

    @Override
    public IconLocalizationDraft localizeIcons(IconLocalizationRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return VisualRulebookPageCatalogModel.super.localizeIcons(request);
        }
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(OpenAiChatOptions.builder()
                    .model(models.modelNameFor(Role.VISUAL, owner))
                    .maxTokens(Math.min(2_000, maxCompletionTokens))
                    .extraBody(Map.of("enable_thinking", false))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()));
        }
        String candidates = iconLocalizationCandidates(request.candidates());
        String content = prompt.system(iconLocalizationPrompt)
                .user(user -> {
                    user.text("""
                                    PDF page number: {pageNumber}
                                    Proposed symbols:
                                    {candidates}
                                    Return exactly one items entry for candidateIndex 0 through {lastCandidateIndex}.
                                    """)
                            .param("pageNumber", request.page().pageNumber())
                            .param("candidates", candidates)
                            .param("lastCandidateIndex", request.candidates().size() - 1);
                    PageImageInput page = images.prepare(request.page());
                    user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()),
                            new ByteArrayResource(page.content()));
                })
                .call()
                .content();
        return parseIconLocalization(content, request.candidates().size());
    }

    @Override
    public IconCropReviewDraft reviewIconCrops(IconCropReviewRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return VisualRulebookPageCatalogModel.super.reviewIconCrops(request);
        }
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(OpenAiChatOptions.builder()
                    .model(models.modelNameFor(Role.VISUAL, owner))
                    .maxTokens(Math.min(1_000, maxCompletionTokens))
                    .extraBody(Map.of("enable_thinking", false))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()));
        }
        String attachmentOrder = java.util.stream.IntStream.range(0, request.candidates().size())
                .mapToObj(index -> "image " + (index + 1) + " = candidateIndex "
                        + request.locations().get(index).candidateIndex() + ", expected appearance="
                        + appearanceWithoutProposedIdentity(request.candidates().get(index)))
                .collect(Collectors.joining("\n"));
        String content = prompt.system(iconCropReviewPrompt)
                .user(user -> {
                    user.text("""
                                    PDF page number: {pageNumber}
                                    Attachment mapping:
                                    {attachmentOrder}
                                    Return exactly one items entry for every listed candidateIndex.
                                    """)
                            .param("pageNumber", request.page().pageNumber())
                            .param("attachmentOrder", attachmentOrder);
                    java.util.stream.IntStream.range(0, request.locations().size())
                            .mapToObj(index -> localizedCrop(request.page(), request.locations().get(index)))
                            .forEach(crop -> user.media(
                                    MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(crop)));
                })
                .call()
                .content();
        IconCropReviewDraft relativeReview = parseIconCropReview(
                content,
                request.locations().stream().map(IconLocation::candidateIndex).toList());
        return projectIconCropReview(relativeReview, request.locations());
    }

    static String iconLocalizationCandidates(List<IconOccurrence> candidates) {
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> {
                    IconOccurrence icon = candidates.get(index);
                    return index + ": visible appearance=" + appearanceWithoutProposedIdentity(icon);
                })
                .collect(Collectors.joining("\n"));
    }

    private static String appearanceWithoutProposedIdentity(IconOccurrence icon) {
        String appearance = icon.visualDescription();
        if (icon.meaningStatus() != IconMeaningStatus.EXPLICIT) return appearance;
        for (String proposedIdentity : List.of(icon.groupKey(), icon.name(), icon.evidenceText())) {
            if (proposedIdentity == null || proposedIdentity.strip().length() < 3) continue;
            appearance = Pattern.compile(Pattern.quote(proposedIdentity.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(appearance)
                    .replaceAll("redacted-label");
        }
        return appearance;
    }

    static IconLocalizationDraft parseIconLocalization(String content, int expectedCandidates) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("visual icon localization model returned no content");
        }
        String json = content.strip();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
                throw new IllegalArgumentException("visual icon localization returned malformed JSON fencing");
            }
            json = json.substring(firstLineEnd + 1, closingFence).strip();
        }
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode items = root.isArray() ? root : root.path("items");
            if (!items.isArray() || items.size() != expectedCandidates) {
                throw new IllegalArgumentException("visual icon localization did not cover every candidate");
            }
            Map<Integer, IconLocation> byCandidate = new java.util.LinkedHashMap<>();
            items.forEach(item -> {
                int index = item.path("candidateIndex").asInt(Integer.MIN_VALUE);
                if (index < 0 || index >= expectedCandidates) {
                    throw new IllegalArgumentException("visual icon localization returned an unknown candidate");
                }
                boolean present = item.path("present").asBoolean(false);
                IconLocation location;
                try {
                    location = present
                            ? new IconLocation(
                                    index,
                                    true,
                                    item.path("x").asInt(Integer.MIN_VALUE),
                                    item.path("y").asInt(Integer.MIN_VALUE),
                                    item.path("width").asInt(Integer.MIN_VALUE),
                                    item.path("height").asInt(Integer.MIN_VALUE),
                                    bounded(joinedText(item.get("observedLabel"), " "), 80))
                            : IconLocation.absent(index);
                } catch (IllegalArgumentException invalidRectangle) {
                    // A single malformed rectangle must not discard other independently verified candidates.
                    location = IconLocation.absent(index);
                }
                if (byCandidate.putIfAbsent(index, location) != null) {
                    throw new IllegalArgumentException("visual icon localization repeated a candidate");
                }
            });
            if (!byCandidate.keySet().equals(
                    java.util.stream.IntStream.range(0, expectedCandidates)
                            .boxed()
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new)))) {
                throw new IllegalArgumentException("visual icon localization returned an unknown candidate");
            }
            return new IconLocalizationDraft(List.copyOf(byCandidate.values()));
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual icon localization returned invalid JSON", invalidJson);
        }
    }

    static IconCropReviewDraft parseIconCropReview(String content, List<Integer> expectedCandidates) {
        if (content == null || content.isBlank() || expectedCandidates == null || expectedCandidates.isEmpty()) {
            throw new IllegalArgumentException("visual icon crop review model returned no content");
        }
        try {
            JsonNode root = JSON.readTree(content.strip());
            JsonNode items = root.isArray() ? root : root.path("items");
            if (!items.isArray() || items.size() != expectedCandidates.size()) {
                throw new IllegalArgumentException("visual icon crop review did not cover every candidate");
            }
            Map<Integer, IconCropDecision> byCandidate = new java.util.LinkedHashMap<>();
            items.forEach(item -> {
                int index = item.path("candidateIndex").asInt(Integer.MIN_VALUE);
                if (!expectedCandidates.contains(index)
                        || byCandidate.putIfAbsent(
                                        index,
                                        item.path("matchesAppearance").asBoolean(false)
                                                ? new IconCropDecision(
                                                        index,
                                                        true,
                                                        item.path("x").asInt(Integer.MIN_VALUE),
                                                        item.path("y").asInt(Integer.MIN_VALUE),
                                                        item.path("width").asInt(Integer.MIN_VALUE),
                                                        item.path("height").asInt(Integer.MIN_VALUE))
                                                : IconCropDecision.rejected(index))
                                != null) {
                    throw new IllegalArgumentException("visual icon crop review returned an unknown candidate");
                }
            });
            if (!byCandidate.keySet().equals(new java.util.LinkedHashSet<>(expectedCandidates))) {
                throw new IllegalArgumentException("visual icon crop review did not cover every candidate");
            }
            return new IconCropReviewDraft(List.copyOf(byCandidate.values()));
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual icon crop review returned invalid JSON", invalidJson);
        }
    }

    private static byte[] localizedCrop(PageImageInput page, IconLocation location) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (source == null) throw new IllegalArgumentException("visual icon crop source cannot be decoded");
            CropBounds bounds = cropBounds(location);
            int left = pixel(bounds.x(), source.getWidth());
            int top = pixel(bounds.y(), source.getHeight());
            int right = pixelCeiling(bounds.x() + bounds.width(), source.getWidth());
            int bottom = pixelCeiling(bounds.y() + bounds.height(), source.getHeight());
            BufferedImage crop = source.getSubimage(left, top, right - left, bottom - top);
            BufferedImage rgb = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(crop, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "jpeg", output)) {
                throw new IllegalStateException("JPEG image writer is unavailable");
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("visual icon crop could not be prepared", failure);
        }
    }

    private static IconCropReviewDraft projectIconCropReview(
            IconCropReviewDraft relativeReview, List<IconLocation> sourceLocations) {
        Map<Integer, IconLocation> sourceByIndex = sourceLocations.stream()
                .collect(Collectors.toMap(IconLocation::candidateIndex, java.util.function.Function.identity()));
        return new IconCropReviewDraft(relativeReview.decisions().stream()
                .map(decision -> {
                    if (!decision.matchesAppearance()) return decision;
                    CropBounds source = cropBounds(sourceByIndex.get(decision.candidateIndex()));
                    int x = source.x() + decision.x() * source.width() / 1_000;
                    int y = source.y() + decision.y() * source.height() / 1_000;
                    int right = source.x()
                            + (decision.x() + decision.width()) * source.width() / 1_000;
                    int bottom = source.y()
                            + (decision.y() + decision.height()) * source.height() / 1_000;
                    try {
                        return new IconCropDecision(
                                decision.candidateIndex(),
                                true,
                                x,
                                y,
                                Math.max(1, right - x),
                                Math.max(1, bottom - y));
                    } catch (IllegalArgumentException invalidProjection) {
                        return IconCropDecision.rejected(decision.candidateIndex());
                    }
                })
                .toList());
    }

    private static CropBounds cropBounds(IconLocation location) {
        int padding = 8;
        int x = Math.max(0, location.x() - padding);
        int y = Math.max(0, location.y() - padding);
        int right = Math.min(1_000, location.x() + location.width() + padding);
        int bottom = Math.min(1_000, location.y() + location.height() + padding);
        return new CropBounds(x, y, right - x, bottom - y);
    }

    private static int pixel(int normalized, int imageSize) {
        return Math.min(imageSize - 1, normalized * imageSize / 1_000);
    }

    private static int pixelCeiling(int normalized, int imageSize) {
        return Math.max(1, Math.min(imageSize, (normalized * imageSize + 999) / 1_000));
    }

    private record CropBounds(int x, int y, int width, int height) {}

    static CatalogDraft parseCatalog(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("visual page catalog model returned no content");
        }
        String json = content.strip();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
                throw new IllegalArgumentException("visual page catalog model returned malformed JSON fencing");
            }
            json = json.substring(firstLineEnd + 1, closingFence).strip();
        }
        try {
            JsonNode pages = JSON.readTree(json).path("pages");
            if (!pages.isArray()) {
                throw new IllegalArgumentException("visual page catalog JSON has no pages array");
            }
            java.util.List<PageSummary> summaries = new java.util.ArrayList<>();
            for (JsonNode page : pages) {
                summaries.add(new PageSummary(
                        page.path("pageNumber").asInt(),
                        bounded(joinedText(page.get("printedTerms"), "; "), 1_600),
                        bounded(joinedText(page.get("factualSummary"), "\n"), 1_600),
                        boundedStrings(page.get("keywords"), 16, 120),
                        visualAnchors(page.get("visualAnchors")),
                        iconOccurrences(page.get("iconOccurrences")),
                        page.path("iconInventoryComplete").asBoolean(false)));
            }
            return new CatalogDraft(summaries);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual page catalog model returned invalid JSON", invalidJson);
        }
    }

    private static String joinedText(JsonNode value, String separator) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isArray()) {
            java.util.List<String> values = new java.util.ArrayList<>();
            value.forEach(item -> {
                if (item.isValueNode() && !item.asText().isBlank()) values.add(item.asText());
            });
            return String.join(separator, values);
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static java.util.List<String> stringValues(JsonNode value) {
        if (value == null || value.isNull()) return java.util.List.of();
        if (value.isArray()) {
            java.util.List<String> values = new java.util.ArrayList<>();
            value.forEach(item -> {
                if (item.isValueNode() && !item.asText().isBlank()) values.add(item.asText());
            });
            return values;
        }
        String text = joinedText(value, "; ");
        return text == null || text.isBlank() ? java.util.List.of() : java.util.List.of(text);
    }

    private static java.util.List<String> boundedStrings(JsonNode value, int maximumItems, int maximumLength) {
        return stringValues(value).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> bounded(item, maximumLength))
                .distinct()
                .limit(maximumItems)
                .toList();
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength).stripTrailing();
    }

    /** Optional anchors must never make a page's textual visual ledger unusable. */
    private static java.util.List<VisualAnchor> visualAnchors(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) return java.util.List.of();
        java.util.List<VisualAnchor> anchors = new java.util.ArrayList<>();
        value.forEach(anchor -> {
            if (anchors.size() == 6 || !anchor.isObject()) return;
            try {
                anchors.add(new VisualAnchor(
                        joinedText(anchor.get("kind"), " "),
                        joinedText(anchor.get("label"), " "),
                        joinedText(anchor.get("visibleDescription"), " "),
                        anchor.path("x").asInt(Integer.MIN_VALUE),
                        anchor.path("y").asInt(Integer.MIN_VALUE),
                        anchor.path("width").asInt(Integer.MIN_VALUE),
                        anchor.path("height").asInt(Integer.MIN_VALUE)));
            } catch (IllegalArgumentException invalidAnchor) {
                // The main page ledger is still usable when an optional model-proposed rectangle is malformed.
            }
        });
        return anchors;
    }

    /** One malformed optional icon must not discard the rest of a page inventory. */
    private static java.util.List<IconOccurrence> iconOccurrences(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) return java.util.List.of();
        java.util.List<IconOccurrence> icons = new java.util.ArrayList<>();
        value.forEach(icon -> {
            if (icons.size() == 32 || !icon.isObject()) return;
            try {
                String rawStatus = joinedText(icon.get("meaningStatus"), " ");
                IconMeaningStatus status = IconMeaningStatus.valueOf(
                        rawStatus == null ? "UNEXPLAINED" : rawStatus.strip().toUpperCase(java.util.Locale.ROOT));
                icons.add(new IconOccurrence(
                        defaultText(joinedText(icon.get("groupKey"), " "), joinedText(icon.get("name"), " ")),
                        defaultText(joinedText(icon.get("name"), " "), "未命名图标"),
                        defaultText(joinedText(icon.get("visualDescription"), " "), "规则书中的一个图标。"),
                        defaultText(joinedText(icon.get("explanation"), " "), ""),
                        defaultText(joinedText(icon.get("evidenceText"), " "), ""),
                        status,
                        icon.path("x").asInt(Integer.MIN_VALUE),
                        icon.path("y").asInt(Integer.MIN_VALUE),
                        icon.path("width").asInt(Integer.MIN_VALUE),
                        icon.path("height").asInt(Integer.MIN_VALUE)));
            } catch (IllegalArgumentException invalidIcon) {
                // Keep every other valid occurrence and let the incomplete flag expose model uncertainty.
            }
        });
        return icons;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static CatalogDraft normalizePageBindings(CatalogRequest request, CatalogDraft draft) {
        if (draft == null) throw new IllegalArgumentException("visual page catalog model returned no draft");
        java.util.List<Integer> requestedOrder = request.pages().stream().map(PageImageInput::pageNumber).toList();
        Set<Integer> requested = Set.copyOf(requestedOrder);
        if (draft.pages().size() != requested.size()) {
            throw new IllegalArgumentException(
                    "visual page catalog did not cover exactly the supplied pages; requested=" + requested
                            + ", items=" + draft.pages().size());
        }

        Map<Integer, Long> returnedCounts = draft.pages().stream().collect(Collectors.groupingBy(
                PageSummary::pageNumber, Collectors.counting()));
        Set<Integer> reservedExactPages = returnedCounts.entrySet().stream()
                .filter(entry -> requested.contains(entry.getKey()) && entry.getValue() == 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        java.util.ArrayDeque<Integer> remainingPages = requestedOrder.stream()
                .filter(page -> !reservedExactPages.contains(page))
                .collect(Collectors.toCollection(java.util.ArrayDeque::new));
        java.util.List<PageSummary> normalized = draft.pages().stream()
                .map(summary -> reservedExactPages.contains(summary.pageNumber())
                        ? summary
                        : rebound(summary, remainingPages.removeFirst()))
                .toList();
        Set<Integer> returned = normalized.stream().map(PageSummary::pageNumber).collect(Collectors.toSet());
        if (!returned.equals(requested)) {
            throw new IllegalArgumentException(
                    "visual page catalog could not bind every supplied page; requested=" + requested
                            + ", returned=" + returned);
        }
        return new CatalogDraft(normalized.stream()
                .sorted(java.util.Comparator.comparingInt(PageSummary::pageNumber))
                .toList());
    }

    private static PageSummary rebound(PageSummary summary, int pageNumber) {
        return new PageSummary(
                pageNumber,
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                summary.visualAnchors(),
                summary.iconOccurrences(),
                summary.iconInventoryComplete());
    }
}
