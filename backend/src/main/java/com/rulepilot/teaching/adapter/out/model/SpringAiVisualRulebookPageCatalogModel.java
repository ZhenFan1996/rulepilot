package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final int maxCompletionTokens;

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models,
            FakeVisualRulebookPageCatalogModel fake,
            @Value("classpath:prompts/visual-page-catalog-v2-icon-inventory-system.txt") Resource systemPrompt,
            @Value("${rulepilot.visual.catalog-max-output-tokens:4800}") int maxCompletionTokens)
            throws IOException {
        this.models = models;
        this.fake = fake;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        if (this.systemPrompt.isBlank()) {
            throw new IllegalArgumentException("visual page catalog prompt must not be blank");
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
                        joinedText(page.get("printedTerms"), "; "),
                        joinedText(page.get("factualSummary"), "\n"),
                        stringValues(page.get("keywords")),
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
