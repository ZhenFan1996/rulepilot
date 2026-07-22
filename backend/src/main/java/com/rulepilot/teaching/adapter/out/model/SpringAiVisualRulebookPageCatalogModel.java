package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Bounded vision pass that turns page images into an auditable, page-numbered planning catalog. */
@Component
@Primary
public class SpringAiVisualRulebookPageCatalogModel implements VisualRulebookPageCatalogModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM = """
            You are a meticulous board-game rulebook visual reader. Inspect only the supplied page images.
            Build a per-page rule evidence ledger for a later planner and writer; do not write a lesson and do not
            invent rules.
            First build an icon legend from any supplied page that explicitly labels components or tokens, then use
            exact visual matching to interpret the same icon on other supplied pages. Never infer an icon's meaning
            from its shape, color, or common board-game convention when a labeled cross-page reference is available.
            In factualSummary, name matched icons with the printed component term from that legend, not with a color,
            shape, emoji, or invented resource name. For stakes, escrow, or temporarily placed components, distinguish
            existing pieces that are kept/returned from newly gained pieces and explicitly report the net change.
            On a legend page, explicitly map each visible icon's color or shape to its exact printed component term.
            For every supplied page return exactly one item with its pageNumber. printedTerms must preserve the visible
            printed headings, labels, action names, component names, icon labels, and numbers in their original language
            (verbatim where readable). factualSummary is a complete Simplified-Chinese evidence ledger of the rules on
            that one page, using original-language terms in parentheses where needed. It may report only what is visibly
            printed or shown, including diagrams and examples.

            For an operational rule page, do not summarize away details: record every visible branch and its exact
            condition, whether it is optional or mandatory, timing and frequency limits, which exact components are
            removed/drawn/replaced/left in place, and the order in which those changes happen. Preserve distinctions
            such as 3 versus 4, any versus all, may versus must, once versus repeatable, and token versus tile. A later
            writer will cite this ledger, so an incomplete generic paraphrase is invalid. Do not infer an unprinted
            convention such as clockwise turn order, a redraw of a different component, or a player-specific setup.
            keywords must contain 2-8 short original-language terms that occur visibly on that same page. If a page is
            a cover, index, illustration, or unreadable, say so explicitly instead of guessing. The page number and every
            reported term must come from an attached page.
            Return structured data only.
            """;

    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models, FakeVisualRulebookPageCatalogModel fake) {
        this.models = models;
        this.fake = fake;
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
                    .extraBody(Map.of("enable_thinking", false))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()));
        }
        String content = prompt.system(SYSTEM)
                .user(user -> {
                    user.text("""
                                    Attached rulebook page numbers: {pageNumbers}
                                    Attachment order: {attachmentOrder}
                                    {crossPageTask}
                                    {correction}
                                    Return a JSON object with a pages array. Each array item must have pageNumber,
                                    printedTerms, factualSummary, and keywords.
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
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
                        stringValues(page.get("keywords"))));
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
                pageNumber, summary.printedTerms(), summary.factualSummary(), summary.keywords());
    }
}
