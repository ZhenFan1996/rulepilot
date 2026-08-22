package com.rulepilot.teaching.adapter.out.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocalizationRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconLocation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropDecision;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.IconCropReviewRequest;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ModelExecutionIdentity;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.PageTranscript;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ProgressiveTeachingStartDraft;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupCoverage;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageRole;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingPageSketch;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.TeachingOutlineModel.SourceCoverageRole;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconMeaningStatus;
import com.rulepilot.teaching.VisualRulebookPageFacts.IconOccurrence;
import com.rulepilot.teaching.VisualRulebookPageFacts.VisualAnchor;
import com.rulepilot.teaching.VisualQuantityObservation;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String QWEN_BALANCED_VISUAL_MODEL = "qwen3.7-plus";
    private static final String TEACHING_CONTRACT_REPAIR = """
            The previous ledger failed deterministic contract validation. Reinspect the attached pages and return one
            complete replacement JSON object. Keep each visible rule group as one ruleGroups object with exactly
            identifier, its same-page fact, and quantitySpans; never return a separate ruleGroupIdentifiers or
            quantityObservations array. quantitySpans contains only exact strings copied from the page and belongs to
            that same ruleGroups item. Do not return kind, indexes, numeric fields, interpreted scope, or calculated total.
            Every identifier must be unique within its page. If one heading governs several distinct groups, use the
            heading once and the shortest exact visible opening phrase for each later group; do not repeat the heading.
            Return externalDocumentDependencies only for a separately named document whose required rules are absent
            from this rulebook; a reference to another page in this same rulebook belongs in ruleGroups instead.
            Every ruleGroups fact containing a rule-significant number, written number, ordinal, threshold, range, or
            worked-example value must bind every such value through one or more strings in its own quantitySpans.
            Written counts such as Chinese “一张牌” and English “two
            actions” require a copied span too. For each ruleGroups item in index order, compare its fact against its
            quantitySpans before returning ruleGroupInventoryComplete=true. Re-read tables row by row. A range remains one literal span,
            never an integer field. Never calculate a replacement total or copy a guessed value into both the fact
            and originalSpan. If a governing value cannot be read, remove the uncertain exact value from the fact and
            do not report the page inventory complete. Return every supplied page and the exact field set as JSON only.
            """;
    private static final Set<String> RULE_GROUP_FIELDS = Set.of("identifier", "fact");
    private static final Set<String> RULE_GROUP_FIELDS_WITH_REDUNDANT_INDEX =
            Set.of("identifier", "fact", "ruleGroupIndex");
    private static final Set<String> QUANTITY_OBSERVATION_FIELDS = Set.of(
            "pageNumber",
            "ruleGroupIdentifier",
            "quantifierScope",
            "variantAxis",
            "variantCount",
            "perVariantQuantity",
            "derivedTotal",
            "originalSpan",
            "resolution");
    private static final Set<String> BOUND_QUANTITY_OBSERVATION_FIELDS = Set.of(
            "pageNumber",
            "ruleGroupIndex",
            "quantifierScope",
            "variantAxis",
            "variantCount",
            "perVariantQuantity",
            "derivedTotal",
            "originalSpan",
            "resolution");
    private static final Set<String> PER_VARIANT_EXACT_QUANTITY_FIELDS = Set.of(
            "kind",
            "pageNumber",
            "ruleGroupIndex",
            "variantAxis",
            "variantCount",
            "perVariantQuantity",
            "originalSpan");
    private static final Set<String> TOTAL_EXACT_QUANTITY_FIELDS = Set.of(
            "kind", "pageNumber", "ruleGroupIndex", "total", "originalSpan");
    private static final Set<String> INSPECTION_QUANTITY_FIELDS = Set.of(
            "kind", "pageNumber", "ruleGroupIndex", "variantAxis", "originalSpan");
    private static final Set<String> LITERAL_QUANTITY_SPAN_FIELDS = Set.of(
            "pageNumber", "ruleGroupIndex", "originalSpan");
    private static final Set<String> TEACHING_V6_ROOT_FIELDS = Set.of("pages");
    private static final Set<String> TEACHING_V6_PAGE_FIELDS = Set.of(
            "pageNumber",
            "printedTerms",
            "keywords",
            "externalDocumentDependencies",
            "ruleGroups",
            "ruleGroupInventoryComplete");
    private static final Set<String> TEACHING_V6_RULE_GROUP_FIELDS =
            Set.of("identifier", "fact", "quantitySpans");
    private static final Set<String> EXTERNAL_DOCUMENT_DEPENDENCY_FIELDS =
            Set.of("documentTitle", "missingCoverageTags");
    private static final Set<String> EXTERNAL_DOCUMENT_COVERAGE_TAGS =
            Set.of("setup", "core_loop", "end", "scoring");
    private static final Set<String> PROGRESSIVE_V4_ROOT_FIELDS =
            Set.of("pageSketches", "selectedPageFacts");
    private static final Set<String> PROGRESSIVE_V4_PAGE_FIELDS = Set.of(
            "pageNumber",
            "role",
            "visibleHeading",
            "visibleTerms",
            "coverageTags",
            "ruleGroupInventoryComplete",
            "sourceDependencies",
            "ruleGroupCoverage");
    private static final Set<String> PROGRESSIVE_V4_SELECTED_FIELDS =
            Set.of("pageNumber", "printedTerms", "ruleGroups", "keywords", "quantityObservations");
    private static final Set<String> SOURCE_DEPENDENCY_FIELDS = Set.of("title", "missingCoverageTags");
    private static final Set<String> PROGRESSIVE_COVERAGE_TAGS =
            Set.of("setup", "core_loop", "end", "scoring", "source_coverage");
    private static final Set<String> ITEMS_ROOT_FIELDS = Set.of("items");
    private static final Set<String> ICON_LOCALIZATION_PRESENT_FIELDS =
            Set.of("candidateIndex", "present", "x", "y", "width", "height", "observedLabel");
    private static final Set<String> ICON_LOCALIZATION_ABSENT_FIELDS =
            Set.of("candidateIndex", "present", "observedLabel");
    private static final Set<String> ICON_CROP_VERDICT_FIELDS =
            Set.of("candidateIndex", "matchesAppearance", "fullyContained", "standalonePictogram");
    private static final Set<String> ICON_CROP_ACCEPTED_FIELDS = Set.of(
            "candidateIndex",
            "matchesAppearance",
            "fullyContained",
            "standalonePictogram",
            "x",
            "y",
            "width",
            "height");
    private static final Set<String> RULE_GROUP_COVERAGE_FIELDS = Set.of("identifier", "role");
    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final String systemPrompt;
    private final String teachingStartupPrompt;
    private final String progressiveTeachingStartPrompt;
    private final String iconLocalizationPrompt;
    private final String iconCropReviewPrompt;
    private final String ocrModelName;
    private final int maxCompletionTokens;

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models,
            FakeVisualRulebookPageCatalogModel fake,
            @Value("classpath:prompts/visual-page-catalog-v2-icon-inventory-system.txt") Resource systemPrompt,
            @Value("classpath:prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt")
                    Resource teachingStartupPrompt,
            @Value("classpath:prompts/visual-page-progressive-teaching-start-v4-source-contract-system.txt")
                    Resource progressiveTeachingStartPrompt,
            @Value("classpath:prompts/visual-icon-localization-v2-system.txt") Resource iconLocalizationPrompt,
            @Value("classpath:prompts/visual-icon-crop-review-v4-system.txt") Resource iconCropReviewPrompt,
            @Value("${rulepilot.visual.ocr-model:qwen3.5-ocr}") String ocrModelName,
            @Value("${rulepilot.visual.catalog-max-output-tokens:4800}") int maxCompletionTokens)
            throws IOException {
        this.models = models;
        this.fake = fake;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.teachingStartupPrompt = teachingStartupPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.progressiveTeachingStartPrompt =
                progressiveTeachingStartPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.iconLocalizationPrompt = iconLocalizationPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.iconCropReviewPrompt = iconCropReviewPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        if (this.systemPrompt.isBlank() || this.teachingStartupPrompt.isBlank()
                || this.progressiveTeachingStartPrompt.isBlank()
                || this.iconLocalizationPrompt.isBlank() || this.iconCropReviewPrompt.isBlank()) {
            throw new IllegalArgumentException("visual page catalog prompts must not be blank");
        }
        if (maxCompletionTokens < 800 || maxCompletionTokens > 8_000) {
            throw new IllegalArgumentException("visual page catalog output budget is invalid");
        }
        if (ocrModelName == null || ocrModelName.isBlank()) {
            throw new IllegalArgumentException("visual page OCR model must not be blank");
        }
        this.ocrModelName = ocrModelName.strip();
        this.maxCompletionTokens = maxCompletionTokens;
    }

    @Override
    public boolean supportsTeachingPageTranscription(String owner) {
        return !models.usesFake(Role.VISUAL, owner)
                && models.supportsVision(Role.VISUAL, owner)
                && "qwen".equals(models.providerFor(Role.VISUAL, owner));
    }

    @Override
    public PageTranscript transcribeTeachingPage(PageImageInput page, String owner) {
        if (!supportsTeachingPageTranscription(owner)) {
            return VisualRulebookPageCatalogModel.super.transcribeTeachingPage(page, owner);
        }
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner))
                .prompt()
                .options(qwenOcrOptions(ocrModelName));
        String content = prompt.user(user -> {
                    user.text("""
                            Copy all visible text from PDF page {pageNumber}. Preserve the original language, line
                            breaks, digits, punctuation, headings, labels, table cells, and worked examples. Output
                            only the copied text. Do not calculate, translate, summarize, repair, or infer missing
                            characters. Write ? for a character that cannot be read reliably.
                            """).param("pageNumber", page.pageNumber());
                    PageImageInput readable = images.prepareForRuleTranscription(page);
                    user.media(
                            MimeTypeUtils.parseMimeType(readable.mediaType()),
                            new ByteArrayResource(readable.content()));
                })
                .call()
                .content();
        return new PageTranscript(page.pageNumber(), content);
    }

    @Override
    public Optional<ModelExecutionIdentity> teachingPageTranscriptionExecutionIdentity(String owner) {
        if (!supportsTeachingPageTranscription(owner)) return Optional.empty();
        return Optional.of(new ModelExecutionIdentity("qwen", ocrModelName));
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

    @Override
    public CatalogDraft summarizeForTeaching(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return fake.summarizeForTeaching(request);
        }
        RuntimeException firstFailure;
        try {
            return normalizeTeachingPageBindings(request, summarizeTeachingOnce(request, owner, ""));
        } catch (RuntimeException failure) {
            // The cataloger already splits a rejected multi-page ledger into single-page requests. Repairing the
            // whole batch here can consume its complete deadline before that more precise fallback can begin.
            if (request.pages().size() > 1) throw failure;
            firstFailure = failure;
        }
        try {
            return normalizeTeachingPageBindings(
                    request,
                    summarizeTeachingOnce(
                            request,
                            owner,
                            TEACHING_CONTRACT_REPAIR + "\nDetected deterministic issue: "
                                    + repairDiagnostic(firstFailure)));
        } catch (RuntimeException failure) {
            failure.addSuppressed(firstFailure);
            throw failure;
        }
    }

    private static String repairDiagnostic(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.strip();
    }

    @Override
    public boolean supportsProgressiveTeachingStart(String owner) {
        return !models.usesFake(Role.VISUAL, owner)
                && models.supportsVision(Role.VISUAL, owner)
                && "qwen".equals(models.providerFor(Role.VISUAL, owner))
                && QWEN_BALANCED_VISUAL_MODEL.equalsIgnoreCase(models.modelNameFor(Role.VISUAL, owner));
    }

    @Override
    public Optional<ProgressiveTeachingStartDraft> selectProgressiveTeachingStart(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (!supportsProgressiveTeachingStart(owner)) return Optional.empty();
        return Optional.of(normalizeProgressiveTeachingStartBindings(
                request, progressiveTeachingStartOnce(request, owner)));
    }

    @Override
    public Optional<ModelExecutionIdentity> teachingStartupExecutionIdentity(String owner) {
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return Optional.empty();
        }
        String provider = models.providerFor(Role.VISUAL, owner);
        String configuredModel = models.modelNameFor(Role.VISUAL, owner);
        return Optional.of(new ModelExecutionIdentity(
                provider, teachingStartupModelName(provider, configuredModel)));
    }

    private CatalogDraft summarizeTeachingOnce(CatalogRequest request, String owner, String contractRepair) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        String provider = models.providerFor(Role.VISUAL, owner);
        if ("qwen".equals(provider)) {
            String configuredModel = models.modelNameFor(Role.VISUAL, owner);
            prompt = prompt.options(qwenJsonOptions(
                    teachingStartupModelName(provider, configuredModel),
                    Math.min(4_800, maxCompletionTokens)));
        }
        String content = prompt.system(teachingStartupPrompt)
                .user(user -> {
                    user.text("""
                                    Supplied PDF page numbers: {pageNumbers}
                                    Rulebook title: {rulebookTitle}
                                    Attachment mapping: {attachmentOrder}
                                    Independent page-bound OCR transcript:
                                    {pageTranscripts}
                                    Use each transcript only to copy literal text from its matching PDF page; use the
                                    attached page to understand layout and which text belongs together. Do not replace
                                    transcript digits with a calculated or inferred value. If a literal value is absent,
                                    marked ?, or visibly conflicts with the image, omit that uncertain value and return
                                    ruleGroupInventoryComplete=false instead of guessing.
                                    Return a JSON object with a pages array. Every returned item must contain only
                                    pageNumber, printedTerms, keywords, externalDocumentDependencies,
                                    ruleGroups, and ruleGroupInventoryComplete. Each ruleGroups item binds one
                                    identifier directly to its fact and its exact quantitySpans. Keep every fact, rule-group object,
                                    and external-document dependency bound to the exact attached page on which it is
                                    visibly supported. Every externalDocumentDependencies item must name a separately
                                    titled file whose required rules are absent from this rulebook. If the page only says
                                    to see another numbered page in this same rulebook, keep that cross-reference in
                                    ruleGroups and return externalDocumentDependencies as an empty array.
                                    Additional contract-repair instructions: {contractRepair}
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
                            .param("rulebookTitle", request.rulebookTitle() == null
                                    ? "not supplied; use only what is visible on each page"
                                    : request.rulebookTitle())
                            .param("contractRepair", contractRepair)
                            .param("attachmentOrder", java.util.stream.IntStream.range(0, request.pages().size())
                                    .mapToObj(index -> "image " + (index + 1) + " = complete PDF page "
                                            + request.pages().get(index).pageNumber())
                                    .collect(Collectors.joining("; ")))
                            .param("pageTranscripts", pageTranscripts(request));
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        return parseTeachingCatalogV6(content);
    }

    private static String pageTranscripts(CatalogRequest request) {
        if (request.transcripts().isEmpty()) return "not supplied; rely on the attached pages without guessing";
        return request.transcripts().stream()
                .map(transcript -> "--- PDF page " + transcript.pageNumber() + " ---\n"
                        + transcript.text()
                        + "\n--- end PDF page " + transcript.pageNumber() + " ---")
                .collect(Collectors.joining("\n"));
    }

    private ProgressiveTeachingStartDraft progressiveTeachingStartOnce(CatalogRequest request, String owner) {
        String provider = models.providerFor(Role.VISUAL, owner);
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(provider)) {
            prompt = prompt.options(qwenJsonOptions(
                    teachingStartupModelName(provider, models.modelNameFor(Role.VISUAL, owner)),
                    Math.min(1_600, maxCompletionTokens)));
        }
        String content = prompt.system(progressiveTeachingStartPrompt)
                .user(user -> {
                    user.text("""
                                    Supplied PDF page numbers: {pageNumbers}
                                    Rulebook title: {rulebookTitle}
                                    Attachment mapping: {attachmentOrder}
                                    Follow the system contract exactly and return JSON only.
                                    """)
                            .param("pageNumbers", request.pages().stream().map(PageImageInput::pageNumber).toList())
                            .param("rulebookTitle", request.rulebookTitle() == null
                                    ? "not supplied; use only what is visible on each page"
                                    : request.rulebookTitle())
                            .param("attachmentOrder", java.util.stream.IntStream.range(0, request.pages().size())
                                    .mapToObj(index -> "image " + (index + 1) + " = PDF page "
                                            + request.pages().get(index).pageNumber())
                                    .collect(java.util.stream.Collectors.joining("; ")));
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        return parseProgressiveTeachingStartV4(content);
    }

    private CatalogDraft summarizeOnce(CatalogRequest request, String owner, String correction) {
        ChatClient.ChatClientRequestSpec prompt = ChatClient.create(models.modelFor(Role.VISUAL, owner)).prompt();
        if ("qwen".equals(models.providerFor(Role.VISUAL, owner))) {
            prompt = prompt.options(
                    qwenJsonOptions(models.modelNameFor(Role.VISUAL, owner), maxCompletionTokens));
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
            prompt = prompt.options(qwenJsonOptions(
                    models.modelNameFor(Role.VISUAL, owner), Math.min(2_000, maxCompletionTokens)));
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
            prompt = prompt.options(qwenJsonOptions(
                    models.modelNameFor(Role.VISUAL, owner), Math.min(1_000, maxCompletionTokens)));
        }
        String attachmentOrder = java.util.stream.IntStream.range(0, request.candidates().size())
                .mapToObj(index -> "image " + (index + 1) + " = candidateIndex "
                        + request.locations().get(index).candidateIndex() + ", expected appearance="
                        + cropReviewAppearance(request.candidates().get(index)))
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

    static String cropReviewAppearance(IconOccurrence icon) {
        return appearanceWithoutProposedIdentity(icon).strip();
    }

    private static String appearanceWithoutProposedIdentity(IconOccurrence icon) {
        String appearance = icon.visualDescription();
        if (icon.meaningStatus() == IconMeaningStatus.UNEXPLAINED) return appearance;
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
        try {
            JsonNode root = JSON.readTree(json);
            requireExactObjectFields(root, ITEMS_ROOT_FIELDS, "visual icon localization root");
            JsonNode items = root.get("items");
            if (!items.isArray() || items.size() != expectedCandidates) {
                throw new IllegalArgumentException("visual icon localization did not cover every candidate");
            }
            Map<Integer, IconLocation> byCandidate = new java.util.LinkedHashMap<>();
            items.forEach(item -> {
                boolean present = requiredBoolean(item.get("present"), "present");
                Set<String> fields = new LinkedHashSet<>();
                item.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(present ? ICON_LOCALIZATION_PRESENT_FIELDS : ICON_LOCALIZATION_ABSENT_FIELDS)
                        && !(fields.equals(ICON_LOCALIZATION_PRESENT_FIELDS) && !present)) {
                    throw new IllegalArgumentException(
                            "visual icon localization item fields do not match its present verdict");
                }
                int index = requiredInteger(item.get("candidateIndex"), "candidateIndex");
                if (index < 0 || index >= expectedCandidates) {
                    throw new IllegalArgumentException("visual icon localization returned an unknown candidate");
                }
                String observedLabel = requiredText(item.get("observedLabel"), "observedLabel", true);
                IconLocation location = present
                        ? new IconLocation(
                                index,
                                true,
                                requiredInteger(item.get("x"), "x"),
                                requiredInteger(item.get("y"), "y"),
                                requiredInteger(item.get("width"), "width"),
                                requiredInteger(item.get("height"), "height"),
                                observedLabel)
                        : IconLocation.absent(index);
                if (!present) {
                    if (!observedLabel.isEmpty()) {
                        throw new IllegalArgumentException(
                                "absent visual icon localization must have an empty observedLabel");
                    }
                    if (fields.equals(ICON_LOCALIZATION_PRESENT_FIELDS)
                            && (requiredInteger(item.get("x"), "x") != 0
                                    || requiredInteger(item.get("y"), "y") != 0
                                    || requiredInteger(item.get("width"), "width") != 0
                                    || requiredInteger(item.get("height"), "height") != 0)) {
                        throw new IllegalArgumentException(
                                "absent visual icon localization coordinates must be zero or omitted");
                    }
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
            requireExactObjectFields(root, ITEMS_ROOT_FIELDS, "visual icon crop review root");
            JsonNode items = root.get("items");
            if (!items.isArray() || items.size() != expectedCandidates.size()) {
                throw new IllegalArgumentException("visual icon crop review did not cover every candidate");
            }
            Map<Integer, IconCropDecision> byCandidate = new java.util.LinkedHashMap<>();
            items.forEach(item -> {
                boolean matchesAppearance = requiredBoolean(item.get("matchesAppearance"), "matchesAppearance");
                boolean fullyContained = requiredBoolean(item.get("fullyContained"), "fullyContained");
                boolean standalonePictogram = requiredBoolean(item.get("standalonePictogram"), "standalonePictogram");
                boolean accepted = matchesAppearance && fullyContained && standalonePictogram;
                Set<String> fields = new LinkedHashSet<>();
                item.fieldNames().forEachRemaining(fields::add);
                if (!fields.equals(accepted ? ICON_CROP_ACCEPTED_FIELDS : ICON_CROP_VERDICT_FIELDS)
                        && !(fields.equals(ICON_CROP_ACCEPTED_FIELDS) && !accepted)) {
                    throw new IllegalArgumentException(
                            "visual icon crop review item fields do not match its verdict");
                }
                int index = requiredInteger(item.get("candidateIndex"), "candidateIndex");
                if (!expectedCandidates.contains(index) || byCandidate.containsKey(index)) {
                    throw new IllegalArgumentException("visual icon crop review returned an unknown candidate");
                }
                IconCropDecision decision = accepted
                        ? new IconCropDecision(
                                index,
                                true,
                                requiredInteger(item.get("x"), "x"),
                                requiredInteger(item.get("y"), "y"),
                                requiredInteger(item.get("width"), "width"),
                                requiredInteger(item.get("height"), "height"))
                        : IconCropDecision.rejected(index);
                if (!accepted && fields.equals(ICON_CROP_ACCEPTED_FIELDS)
                        && (requiredInteger(item.get("x"), "x") != 0
                                || requiredInteger(item.get("y"), "y") != 0
                                || requiredInteger(item.get("width"), "width") != 0
                                || requiredInteger(item.get("height"), "height") != 0)) {
                    throw new IllegalArgumentException(
                            "rejected visual icon crop coordinates must be zero or omitted");
                }
                byCandidate.put(index, decision);
            });
            if (!byCandidate.keySet().equals(new java.util.LinkedHashSet<>(expectedCandidates))) {
                throw new IllegalArgumentException("visual icon crop review did not cover every candidate");
            }
            return new IconCropReviewDraft(List.copyOf(byCandidate.values()));
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual icon crop review returned invalid JSON", invalidJson);
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
            int largestEdge = Math.max(crop.getWidth(), crop.getHeight());
            double scale = largestEdge < 512 ? 512.0 / largestEdge : 1.0;
            int outputWidth = Math.max(1, (int) Math.round(crop.getWidth() * scale));
            int outputHeight = Math.max(1, (int) Math.round(crop.getHeight() * scale));
            BufferedImage rgb = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(crop, 0, 0, outputWidth, outputHeight, null);
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

    private static CropBounds cropBounds(IconLocation location) {
        // Keep the independently localized object central while showing enough edge context to expose clipping.
        int padding = 16;
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

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName, int maxTokens) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                // Spatial extraction and binary publication decisions must be replayable. Provider-default sampling
                // made the same page alternate between valid rectangles, malformed JSON, and rejected crops.
                .temperature(0.0)
                .maxTokens(maxTokens)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    static OpenAiChatOptions.Builder qwenOcrOptions(String modelName) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                // Qwen-OCR documents 0.01 as its deterministic default. Do not send JSON response-format or
                // thinking extensions: OCR returns literal text and does not support a custom system message.
                .temperature(0.01);
    }

    static String teachingStartupModelName(String provider, String configuredModel) {
        return configuredModel;
    }

    private record CropBounds(int x, int y, int width, int height) {}

    static CatalogDraft parseCatalog(String content) {
        return parseCatalog(content, false, false);
    }

    static CatalogDraft parseTeachingCatalog(String content) {
        return parseCatalog(content, true, false);
    }

    static CatalogDraft parseTeachingCatalogV3(String content) {
        return parseCatalog(content, true, true);
    }

    static CatalogDraft parseTeachingCatalogV4(String content) {
        return parseCatalog(content, true, true, true);
    }

    static CatalogDraft parseTeachingCatalogV5(String content) {
        return parseCatalog(content, true, true, true, true);
    }

    static CatalogDraft parseTeachingCatalogV6(String content) {
        return parseCatalog(inlineQuantitySpansAsObservations(content), true, true, true, false, true);
    }

    private static String inlineQuantitySpansAsObservations(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("visual page catalog model returned no content");
        }
        String json = content.strip();
        try {
            JsonNode root = JSON.readTree(json);
            requireExactObjectFields(root, TEACHING_V6_ROOT_FIELDS, "visual teaching catalog root");
            JsonNode pages = root.get("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                throw new IllegalArgumentException("visual teaching catalog must return a non-empty pages array");
            }
            for (JsonNode page : pages) {
                if (!(page instanceof ObjectNode objectPage)) {
                    throw new IllegalArgumentException("visual teaching catalog page must be an object");
                }
                requireExactObjectFields(page, TEACHING_V6_PAGE_FIELDS, "visual teaching catalog page");
                int pageNumber = requiredInteger(page.get("pageNumber"), "pageNumber");
                if (pageNumber < 1) {
                    throw new IllegalArgumentException("visual teaching catalog pageNumber must be positive");
                }
                strictTextArray(page.get("printedTerms"), "printedTerms", 0, 12);
                strictTextArray(page.get("keywords"), "keywords", 2, 8);
                strictExternalDocumentDependencies(page.get("externalDocumentDependencies"));
                if (!page.get("ruleGroupInventoryComplete").isBoolean()) {
                    throw new IllegalArgumentException(
                            "visual teaching catalog ruleGroupInventoryComplete must be boolean");
                }
                JsonNode groups = page.get("ruleGroups");
                if (groups == null || !groups.isArray() || groups.size() > 32) {
                    throw new IllegalArgumentException("visual teaching catalog ruleGroups must be an array of at most 32 items");
                }
                for (JsonNode group : groups) {
                    requireExactObjectFields(
                            group, TEACHING_V6_RULE_GROUP_FIELDS, "visual teaching catalog ruleGroups item");
                    requiredText(group.get("identifier"), "identifier", false);
                    String fact = requiredText(group.get("fact"), "fact", false);
                    if (fact.indexOf('\n') >= 0 || fact.indexOf('\r') >= 0) {
                        throw new IllegalArgumentException(
                                "visual teaching catalog ruleGroups fact must be a single line");
                    }
                    List<String> quantitySpans = strictTextArray(
                            group.get("quantitySpans"), "quantitySpans", 0, Integer.MAX_VALUE);
                    if (quantitySpans.stream().anyMatch(span -> span.length() > 240
                            || span.indexOf('\n') >= 0
                            || span.indexOf('\r') >= 0)) {
                        throw new IllegalArgumentException(
                                "visual teaching catalog quantitySpans must be single-line text of at most 240 characters");
                    }
                }
                ArrayNode observations = JSON.createArrayNode();
                for (int index = 0; index < groups.size(); index++) {
                    JsonNode group = groups.get(index);
                    if (!(group instanceof ObjectNode objectGroup)) {
                        throw new IllegalArgumentException("visual teaching catalog ruleGroups item must be an object");
                    }
                    JsonNode spans = objectGroup.remove("quantitySpans");
                    if (!spans.isArray()) {
                        throw new IllegalArgumentException(
                                "visual teaching catalog ruleGroups quantitySpans must be an array");
                    }
                    for (JsonNode span : spans) {
                        if (!span.isTextual()) {
                            throw new IllegalArgumentException(
                                    "visual teaching catalog ruleGroups quantitySpans must contain text");
                        }
                        ObjectNode observation = JSON.createObjectNode();
                        observation.put("pageNumber", pageNumber);
                        observation.put("ruleGroupIndex", index);
                        observation.put("originalSpan", span.textValue());
                        observations.add(observation);
                    }
                }
                objectPage.putArray("factualSummary");
                objectPage.set("quantityObservations", observations);
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual page catalog model returned invalid JSON", invalidJson);
        }
    }

    private static void strictExternalDocumentDependencies(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 4) {
            throw new IllegalArgumentException(
                    "visual teaching catalog externalDocumentDependencies must be an array of at most four items");
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (JsonNode dependency : value) {
            requireExactObjectFields(
                    dependency,
                    EXTERNAL_DOCUMENT_DEPENDENCY_FIELDS,
                    "visual teaching catalog externalDocumentDependencies item");
            String title = requiredText(dependency.get("documentTitle"), "documentTitle", false).strip();
            if (title.length() > 160 || !titles.add(title)) {
                throw new IllegalArgumentException(
                        "visual teaching catalog external document title is invalid or duplicated");
            }
            List<String> coverage = strictTextArray(
                    dependency.get("missingCoverageTags"), "missingCoverageTags", 0, 4);
            if (!EXTERNAL_DOCUMENT_COVERAGE_TAGS.containsAll(coverage)) {
                throw new IllegalArgumentException(
                        "visual teaching catalog external document coverage tag is unknown");
            }
        }
    }

    private static void strictSourceDependencies(JsonNode value, String titleField) {
        if (value == null || !value.isArray() || value.size() > 4) {
            throw new IllegalArgumentException(
                    "progressive visual teaching sourceDependencies must be an array of at most four items");
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (JsonNode dependency : value) {
            requireExactObjectFields(
                    dependency, SOURCE_DEPENDENCY_FIELDS, "progressive visual teaching sourceDependencies item");
            String title = requiredText(dependency.get(titleField), titleField, false).strip();
            if (title.length() > 160 || !titles.add(title)) {
                throw new IllegalArgumentException(
                        "progressive visual teaching source dependency title is invalid or duplicated");
            }
            List<String> coverage = strictTextArray(
                    dependency.get("missingCoverageTags"), "missingCoverageTags", 0, 4);
            if (!EXTERNAL_DOCUMENT_COVERAGE_TAGS.containsAll(coverage)) {
                throw new IllegalArgumentException(
                        "progressive visual teaching source dependency coverage tag is unknown");
            }
        }
    }

    private static List<String> strictTextArray(
            JsonNode value, String field, int minimumSize, int maximumSize) {
        if (value == null || !value.isArray() || value.size() < minimumSize || value.size() > maximumSize) {
            throw new IllegalArgumentException(field + " must be an array with the declared size");
        }
        List<String> values = new java.util.ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank text");
            }
            String text = item.textValue().strip();
            if (!distinct.add(text)) {
                throw new IllegalArgumentException(field + " must not contain duplicate text");
            }
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static void requireExactObjectFields(JsonNode value, Set<String> expected, String contract) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(contract + " must be an object");
        }
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(contract + " must contain exactly " + expected);
        }
    }

    private static CatalogDraft parseCatalog(
            String content, boolean requireSourceDependencies, boolean requireQuantityObservations) {
        return parseCatalog(content, requireSourceDependencies, requireQuantityObservations, false);
    }

    private static CatalogDraft parseCatalog(
            String content,
            boolean requireSourceDependencies,
            boolean requireQuantityObservations,
            boolean requireBoundRuleGroups) {
        return parseCatalog(
                content,
                requireSourceDependencies,
                requireQuantityObservations,
                requireBoundRuleGroups,
                false,
                false);
    }

    private static CatalogDraft parseCatalog(
            String content,
            boolean requireSourceDependencies,
            boolean requireQuantityObservations,
            boolean requireBoundRuleGroups,
            boolean requireDiscriminatedQuantities) {
        return parseCatalog(
                content,
                requireSourceDependencies,
                requireQuantityObservations,
                requireBoundRuleGroups,
                requireDiscriminatedQuantities,
                false);
    }

    private static CatalogDraft parseCatalog(
            String content,
            boolean requireSourceDependencies,
            boolean requireQuantityObservations,
            boolean requireBoundRuleGroups,
            boolean requireDiscriminatedQuantities,
            boolean requireLiteralQuantitySpans) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("visual page catalog model returned no content");
        }
        String json = content.strip();
        try {
            JsonNode pages = JSON.readTree(json).path("pages");
            if (!pages.isArray()) {
                throw new IllegalArgumentException("visual page catalog JSON has no pages array");
            }
            java.util.List<PageSummary> summaries = new java.util.ArrayList<>();
            for (JsonNode page : pages) {
                JsonNode sourceDependencyInventory = requireLiteralQuantitySpans
                        ? page.get("externalDocumentDependencies")
                        : page.get("sourceDependencies");
                if (requireSourceDependencies
                        && (sourceDependencyInventory == null || !sourceDependencyInventory.isArray())) {
                    throw new IllegalArgumentException(requireLiteralQuantitySpans
                            ? "visual teaching catalog must return externalDocumentDependencies for every page"
                            : "visual teaching catalog must return sourceDependencies for every page");
                }
                boolean externalDocumentShape = requireLiteralQuantitySpans
                        && page.has("externalDocumentDependencies");
                List<SourceDependency> dependencies = sourceDependencies(
                        sourceDependencyInventory, externalDocumentShape ? "documentTitle" : "title");
                JsonNode ruleGroupInventory = page.get("ruleGroupIdentifiers");
                int pageNumber = page.path("pageNumber").asInt();
                List<RuleGroupFact> boundRuleGroups = requireBoundRuleGroups
                        ? strictBoundRuleGroups(pageNumber, page.get("ruleGroups"))
                        : List.of();
                JsonNode ruleGroupCompleteness = page.get("ruleGroupInventoryComplete");
                if (requireSourceDependencies && !requireBoundRuleGroups
                        && (ruleGroupInventory == null || !ruleGroupInventory.isArray())) {
                    throw new IllegalArgumentException(
                            "visual teaching catalog must return ruleGroupIdentifiers for every page");
                }
                if (requireSourceDependencies
                        && (ruleGroupCompleteness == null || !ruleGroupCompleteness.isBoolean())) {
                    throw new IllegalArgumentException(
                            "visual teaching catalog must return ruleGroupInventoryComplete for every page");
                }
                List<String> ruleGroupIdentifiers = requireBoundRuleGroups
                        ? boundRuleGroups.stream().map(RuleGroupFact::identifier).toList()
                        : requireSourceDependencies
                                ? strictRuleGroupIdentifiers(ruleGroupInventory)
                        : ruleGroupInventory != null && ruleGroupInventory.isArray()
                                ? normalizedStrings(ruleGroupInventory)
                                : List.of();
                boolean ruleGroupInventoryComplete = ruleGroupCompleteness != null
                        && ruleGroupCompleteness.isBoolean()
                        && ruleGroupCompleteness.booleanValue();
                List<VisualQuantityObservation> quantityObservations = requireBoundRuleGroups
                        ? requireLiteralQuantitySpans
                                ? literalBoundQuantitySpans(
                                        page.get("quantityObservations"), requireQuantityObservations, boundRuleGroups)
                                : requireDiscriminatedQuantities
                                ? discriminatedBoundQuantityObservations(
                                        page.get("quantityObservations"), requireQuantityObservations, boundRuleGroups)
                                : boundQuantityObservations(
                                        page.get("quantityObservations"), requireQuantityObservations, boundRuleGroups)
                        : quantityObservations(page.get("quantityObservations"), requireQuantityObservations);
                String printedTerms = joinedText(page.get("printedTerms"), "; ");
                String factualSummary = joinedText(page.get("factualSummary"), "\n");
                if (requireBoundRuleGroups && !boundRuleGroups.isEmpty()) {
                    String boundFacts = boundRuleGroups.stream()
                            .map(group -> group.identifier() + ": [" + group.label() + "] " + group.fact())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    factualSummary = factualSummary.isBlank()
                            ? boundFacts
                            : factualSummary + "\n" + boundFacts;
                }
                if (!dependencies.isEmpty()) {
                    String dependencyTitles = dependencies.stream()
                            .map(SourceDependency::title)
                            .collect(java.util.stream.Collectors.joining("; "));
                    String dependencyFacts = dependencies.stream()
                            .map(SpringAiVisualRulebookPageCatalogModel::sourceDependencyFact)
                            .collect(java.util.stream.Collectors.joining("\n"));
                    printedTerms = printedTerms.isBlank()
                            ? dependencyTitles
                            : dependencyTitles + "; " + printedTerms;
                    factualSummary = factualSummary.isBlank()
                            ? dependencyFacts
                            : dependencyFacts + "\n" + factualSummary;
                }
                if (ruleGroupInventoryComplete && requireBoundRuleGroups) {
                    validateRuleGroupFactBindings(ruleGroupIdentifiers, boundRuleGroups, "visual teaching");
                }
                // Historical V2/V3 fixtures supplied an identifier array plus prose. They remain readable as partial
                // observations, but cannot claim a complete schema-36 ledger because the application no longer
                // reconstructs JSON relationships from that prose.
                ruleGroupInventoryComplete = ruleGroupInventoryComplete && requireBoundRuleGroups;
                ParsedIconInventory parsedIcons = iconOccurrences(page.get("iconOccurrences"));
                summaries.add(new PageSummary(
                        pageNumber,
                        printedTerms,
                        factualSummary,
                        normalizedStrings(page.get("keywords")),
                        visualAnchors(page.get("visualAnchors")),
                        parsedIcons.occurrences(),
                        page.path("iconInventoryComplete").isBoolean()
                                && page.path("iconInventoryComplete").booleanValue()
                                && parsedIcons.contractValid(),
                        dependencies,
                        ruleGroupIdentifiers,
                        ruleGroupInventoryComplete,
                        quantityObservations,
                        boundRuleGroups));
            }
            return new CatalogDraft(summaries);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("visual page catalog model returned invalid JSON", invalidJson);
        }
    }

    private static List<String> strictRuleGroupIdentifiers(JsonNode value) {
        return strictIdentifiers(value, "visual teaching catalog");
    }

    private static List<RuleGroupFact> strictBoundRuleGroups(int pageNumber, JsonNode value) {
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("visual teaching catalog pageNumber must be positive");
        }
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("visual teaching catalog must return ruleGroups for every page");
        }
        List<RuleGroupFact> groups = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> exactGroups = new java.util.LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("visual teaching catalog ruleGroups item must be an object");
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            boolean hasRedundantIndex = fields.equals(RULE_GROUP_FIELDS_WITH_REDUNDANT_INDEX);
            if (!fields.equals(RULE_GROUP_FIELDS) && !hasRedundantIndex) {
                throw new IllegalArgumentException(
                        "visual teaching catalog ruleGroups item must contain exactly identifier and fact");
            }
            if (hasRedundantIndex
                    && requiredInteger(item.get("ruleGroupIndex"), "ruleGroupIndex") != groups.size()) {
                throw new IllegalArgumentException(
                        "visual teaching catalog redundant ruleGroupIndex must equal the ruleGroups array position");
            }
            String label = requiredText(item.get("identifier"), "identifier", false).strip();
            String fact = requiredText(item.get("fact"), "fact", false).strip();
            String exactGroup = VisualSourceRuleGroupLedger.identity(label)
                    + "\u0000"
                    + VisualSourceRuleGroupLedger.identity(fact);
            if (!exactGroups.add(exactGroup)) {
                throw new IllegalArgumentException(
                        "visual teaching catalog contains an exactly duplicated rule group");
            }
            if (fact.indexOf('\n') >= 0 || fact.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("visual teaching catalog rule-group fact must be a single line");
            }
            groups.add(new RuleGroupFact(
                    "page-" + pageNumber + "-group-" + (groups.size() + 1), label, fact));
        }
        return List.copyOf(groups);
    }

    private static List<String> strictIdentifiers(JsonNode value, String contract) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(contract + " must return a rule-group identifier array");
        }
        List<String> identifiers = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> identities = new java.util.LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(contract + " rule-group identifier must be text");
            }
            String identifier = item.textValue().strip();
            String identity = VisualSourceRuleGroupLedger.identity(identifier);
            if (identifier.isBlank() || !identities.add(identity)) {
                throw new IllegalArgumentException(contract + " rule-group identifier is invalid or duplicated");
            }
            identifiers.add(identifier);
        }
        return List.copyOf(identifiers);
    }

    static ProgressiveTeachingStartDraft parseProgressiveTeachingStart(String content) {
        return parseProgressiveTeachingStart(content, false, false);
    }

    static ProgressiveTeachingStartDraft parseProgressiveTeachingStartV3(String content) {
        return parseProgressiveTeachingStart(content, true, false);
    }

    static ProgressiveTeachingStartDraft parseProgressiveTeachingStartV4(String content) {
        return parseProgressiveTeachingStart(content, true, true);
    }

    private static ProgressiveTeachingStartDraft parseProgressiveTeachingStart(
            String content, boolean requireQuantityObservations, boolean requireRuleGroupCoverage) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("progressive visual teaching model returned no content");
        }
        String json = content.strip();
        try {
            JsonNode root = JSON.readTree(json);
            if (requireRuleGroupCoverage) {
                requireExactObjectFields(root, PROGRESSIVE_V4_ROOT_FIELDS, "progressive visual teaching root");
            }
            JsonNode pageSketches = root.path("pageSketches");
            JsonNode selected = root.path("selectedPageFacts");
            if (!pageSketches.isArray() || pageSketches.isEmpty() || !selected.isObject()) {
                throw new IllegalArgumentException("progressive visual teaching JSON is incomplete");
            }
            if (requireRuleGroupCoverage) {
                requireExactObjectFields(
                        selected, PROGRESSIVE_V4_SELECTED_FIELDS, "progressive visual teaching selectedPageFacts");
            }
            List<TeachingPageSketch> sketches = new java.util.ArrayList<>();
            for (JsonNode page : pageSketches) {
                if (requireRuleGroupCoverage) {
                    requireExactObjectFields(
                            page, PROGRESSIVE_V4_PAGE_FIELDS, "progressive visual teaching pageSketches item");
                }
                JsonNode sourceDependencyInventory = page.get("sourceDependencies");
                if (sourceDependencyInventory == null || !sourceDependencyInventory.isArray()) {
                    throw new IllegalArgumentException(
                            "progressive visual teaching must return sourceDependencies for every page");
                }
                TeachingPageRole role;
                try {
                    role = TeachingPageRole.valueOf(requiredText(page.get("role"), "role", false));
                } catch (IllegalArgumentException invalidRole) {
                    throw new IllegalArgumentException("progressive visual teaching returned an unknown page role");
                }
                if (requireRuleGroupCoverage) {
                    int pageNumber = requiredInteger(page.get("pageNumber"), "pageNumber");
                    if (pageNumber < 1) {
                        throw new IllegalArgumentException("progressive visual teaching pageNumber must be positive");
                    }
                    requiredText(page.get("visibleHeading"), "visibleHeading", true);
                    strictSourceDependencies(sourceDependencyInventory, "title");
                }
                JsonNode inventoryCompleteNode = page.get("ruleGroupInventoryComplete");
                if (requireRuleGroupCoverage && (inventoryCompleteNode == null || !inventoryCompleteNode.isBoolean())) {
                    throw new IllegalArgumentException(
                            "progressive visual teaching ruleGroupInventoryComplete must be boolean");
                }
                boolean ruleGroupInventoryComplete = inventoryCompleteNode != null
                        && inventoryCompleteNode.isBoolean()
                        && inventoryCompleteNode.booleanValue();
                List<String> visibleTerms = requireRuleGroupCoverage
                        ? strictTextArray(page.get("visibleTerms"), "visibleTerms", 0, 8)
                        : ruleGroupInventoryComplete
                                ? strictIdentifiers(page.get("visibleTerms"), "progressive visual teaching")
                                : normalizedStrings(page.get("visibleTerms"));
                List<String> coverageTags = requireRuleGroupCoverage
                        ? strictTextArray(page.get("coverageTags"), "coverageTags", 0, 5)
                        : normalizedStrings(page.get("coverageTags"));
                if (requireRuleGroupCoverage && !PROGRESSIVE_COVERAGE_TAGS.containsAll(coverageTags)) {
                    throw new IllegalArgumentException("progressive visual teaching coverageTags contain an unknown value");
                }
                List<RuleGroupCoverage> ruleGroupCoverage = ruleGroupCoverage(
                        page.get("ruleGroupCoverage"), requireRuleGroupCoverage);
                if (requireRuleGroupCoverage) {
                    List<String> classifiedTerms = ruleGroupCoverage.stream()
                            .map(RuleGroupCoverage::identifier)
                            .toList();
                    if (role == TeachingPageRole.GAMEPLAY_RULES) {
                        if (!classifiedTerms.equals(visibleTerms)) {
                            throw new IllegalArgumentException(
                                    "progressive visual teaching must classify every visibleTerms identifier in order");
                        }
                    } else if (!visibleTerms.isEmpty() || !coverageTags.isEmpty() || !ruleGroupCoverage.isEmpty()
                            || ruleGroupInventoryComplete) {
                        throw new IllegalArgumentException(
                                "progressive visual teaching non-gameplay page must not claim rule coverage");
                    }
                }
                sketches.add(new TeachingPageSketch(
                        requireRuleGroupCoverage
                                ? requiredInteger(page.get("pageNumber"), "pageNumber")
                                : page.path("pageNumber").asInt(),
                        role,
                        requireRuleGroupCoverage
                                ? requiredText(page.get("visibleHeading"), "visibleHeading", true)
                                : joinedText(page.get("visibleHeading"), " "),
                        visibleTerms,
                        coverageTags,
                        ruleGroupInventoryComplete,
                        sourceDependencies(sourceDependencyInventory),
                        ruleGroupCoverage));
            }
            int selectedPageNumber = requireRuleGroupCoverage
                    ? requiredInteger(selected.get("pageNumber"), "pageNumber")
                    : selected.path("pageNumber").asInt();
            TeachingPageSketch selectedSketch = sketches.stream()
                    .filter(page -> page.pageNumber() == selectedPageNumber)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "progressive visual teaching selected an unknown source page"));
            if (requireRuleGroupCoverage && selectedSketch.role() != TeachingPageRole.GAMEPLAY_RULES) {
                throw new IllegalArgumentException(
                        "progressive visual teaching must select a GAMEPLAY_RULES page");
            }
            List<SourceDependency> selectedDependencies = selectedSketch.sourceDependencies();
            String printedTerms = requireRuleGroupCoverage
                    ? String.join("; ", strictTextArray(selected.get("printedTerms"), "printedTerms", 0, 8))
                    : joinedText(selected.get("printedTerms"), "; ");
            List<RuleGroupFact> selectedRuleGroups = requireRuleGroupCoverage
                    ? strictProgressiveRuleGroups(selected.get("ruleGroups"))
                    : List.of();
            if (requireRuleGroupCoverage && selectedRuleGroups.isEmpty()) {
                throw new IllegalArgumentException(
                        "progressive visual teaching selected page must contain a readable rule group");
            }
            List<String> selectedRuleGroupIdentifiers = requireRuleGroupCoverage
                    ? selectedRuleGroups.stream().map(RuleGroupFact::identifier).toList()
                    : selectedSketch.visibleTerms();
            if (requireRuleGroupCoverage && !selectedRuleGroupIdentifiers.equals(selectedSketch.visibleTerms())) {
                throw new IllegalArgumentException(
                        "progressive visual teaching selected ruleGroups must match visibleTerms exactly");
            }
            String factualSummary = requireRuleGroupCoverage
                    ? selectedRuleGroups.stream()
                            .map(group -> group.identifier() + ": " + group.fact())
                            .collect(java.util.stream.Collectors.joining("\n"))
                    : joinedText(selected.get("factualSummary"), "\n");
            if (!selectedDependencies.isEmpty()) {
                String dependencyTitles = selectedDependencies.stream()
                        .map(SourceDependency::title)
                        .collect(java.util.stream.Collectors.joining("; "));
                String dependencyFacts = selectedDependencies.stream()
                        .map(SpringAiVisualRulebookPageCatalogModel::sourceDependencyFact)
                        .collect(java.util.stream.Collectors.joining("\n"));
                printedTerms = printedTerms.isBlank() ? dependencyTitles : printedTerms + "; " + dependencyTitles;
                factualSummary = factualSummary.isBlank() ? dependencyFacts : factualSummary + "\n" + dependencyFacts;
            }
            factualSummary = factualSummary == null ? "" : factualSummary.strip();
            boolean selectedInventoryComplete = selectedSketch.ruleGroupInventoryComplete() && requireRuleGroupCoverage;
            if (selectedInventoryComplete) {
                validateRuleGroupFactBindings(
                        selectedRuleGroupIdentifiers, selectedRuleGroups, "progressive visual teaching");
                if (!selectedRuleGroupIdentifiers.equals(selectedSketch.visibleTerms())) {
                    throw new IllegalArgumentException(
                            "progressive visual teaching selected ruleGroups must match visibleTerms exactly");
                }
            }
            List<VisualQuantityObservation> quantityObservations = quantityObservations(
                    selected.get("quantityObservations"), requireQuantityObservations);
            List<String> selectedKeywords = requireRuleGroupCoverage
                    ? strictTextArray(selected.get("keywords"), "keywords", 2, 6)
                    : normalizedStrings(selected.get("keywords"));
            if (requireRuleGroupCoverage && quantityObservations.size() > 8) {
                throw new IllegalArgumentException(
                        "progressive visual teaching quantityObservations must have at most eight items");
            }
            if (requireRuleGroupCoverage && quantityObservations.stream().anyMatch(observation ->
                    observation.pageNumber() != selectedPageNumber
                            || !selectedRuleGroupIdentifiers.contains(observation.ruleGroupIdentifier()))) {
                throw new IllegalArgumentException(
                        "progressive visual teaching quantity observation is not bound to the selected rule group");
            }
            return new ProgressiveTeachingStartDraft(
                    sketches,
                    new PageSummary(
                            selectedPageNumber,
                            printedTerms,
                            factualSummary,
                            selectedKeywords,
                            List.of(),
                            List.of(),
                            false,
                            selectedDependencies,
                            selectedRuleGroupIdentifiers,
                            selectedInventoryComplete,
                            quantityObservations,
                            selectedRuleGroups));
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("progressive visual teaching returned invalid JSON", invalidJson);
        }
    }

    private static List<RuleGroupCoverage> ruleGroupCoverage(JsonNode value, boolean required) {
        if (value == null || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException(
                        "progressive visual teaching must return ruleGroupCoverage for every page");
            }
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("progressive visual teaching ruleGroupCoverage is invalid");
        }
        List<RuleGroupCoverage> coverage = new java.util.ArrayList<>();
        LinkedHashSet<String> classifiedIdentifiers = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "progressive visual teaching ruleGroupCoverage item must be an object");
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(RULE_GROUP_COVERAGE_FIELDS)) {
                throw new IllegalArgumentException(
                        "progressive visual teaching ruleGroupCoverage item must contain the exact fields");
            }
            try {
                String identifier = requiredText(item.get("identifier"), "identifier", false);
                if (!classifiedIdentifiers.add(identifier)) {
                    throw new IllegalArgumentException("identifier is duplicated");
                }
                coverage.add(new RuleGroupCoverage(
                        identifier,
                        SourceCoverageRole.valueOf(requiredText(item.get("role"), "role", false))));
            } catch (IllegalArgumentException invalidCoverage) {
                throw new IllegalArgumentException(
                        "progressive visual teaching ruleGroupCoverage item is invalid: "
                                + invalidCoverage.getMessage(),
                        invalidCoverage);
            }
        }
        return List.copyOf(coverage);
    }

    private static List<RuleGroupFact> strictProgressiveRuleGroups(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("progressive visual teaching must return selected ruleGroups");
        }
        List<RuleGroupFact> facts = new java.util.ArrayList<>();
        Set<String> identifiers = new java.util.LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("progressive visual teaching ruleGroups item must be an object");
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(RULE_GROUP_FIELDS)) {
                throw new IllegalArgumentException(
                        "progressive visual teaching ruleGroups item must contain exactly identifier and fact");
            }
            String identifier = requiredText(item.get("identifier"), "identifier", false);
            if (!identifiers.add(identifier)) {
                throw new IllegalArgumentException("progressive visual teaching duplicated a ruleGroups identifier");
            }
            facts.add(new RuleGroupFact(
                    identifier,
                    identifier,
                    requiredText(item.get("fact"), "fact", false)));
        }
        return List.copyOf(facts);
    }

    private static void validateRuleGroupFactBindings(
            List<String> identifiers, List<RuleGroupFact> facts, String contract) {
        if (VisualSourceRuleGroupLedger.hasExactFactBindings(identifiers, facts)) return;
        for (String identifier : identifiers) {
            if (!VisualSourceRuleGroupLedger.hasExactFactBinding(identifier, facts)) {
                throw new IllegalArgumentException(
                        contract + " rule group has no same-page fact: " + identifier);
            }
        }
    }

    private static JsonNode firstPresent(JsonNode object, String preferred, String legacy) {
        JsonNode preferredValue = object.get(preferred);
        return preferredValue == null ? object.get(legacy) : preferredValue;
    }

    private static List<SourceDependency> sourceDependencies(JsonNode value) {
        return sourceDependencies(value, "title");
    }

    private static List<SourceDependency> sourceDependencies(JsonNode value, String titleField) {
        if (value == null || value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) {
            throw new IllegalArgumentException("visual teaching source dependencies are invalid");
        }
        List<SourceDependency> dependencies = new java.util.ArrayList<>();
        for (JsonNode dependency : value) {
            if (!dependency.isObject()) {
                throw new IllegalArgumentException("visual teaching source dependency is invalid");
            }
            JsonNode titleValue = dependency.get(titleField);
            if (titleValue == null || !titleValue.isTextual() || titleValue.textValue().isBlank()) {
                throw new IllegalArgumentException("visual teaching source dependency title is invalid");
            }
            JsonNode coverageValues = dependency.get("missingCoverageTags");
            if (coverageValues == null || !coverageValues.isArray()) {
                throw new IllegalArgumentException(
                        "visual teaching source dependency missingCoverageTags are invalid");
            }
            List<String> coverageTags = new java.util.ArrayList<>();
            java.util.LinkedHashSet<String> distinctCoverageTags = new java.util.LinkedHashSet<>();
            for (JsonNode coverageValue : coverageValues) {
                if (!coverageValue.isTextual()) {
                    throw new IllegalArgumentException(
                            "visual teaching source dependency missingCoverageTags must contain text");
                }
                String coverageTag = coverageValue.textValue().strip();
                if (coverageTag.isBlank() || !distinctCoverageTags.add(coverageTag)) {
                    throw new IllegalArgumentException(
                            "visual teaching source dependency missingCoverageTags are invalid or duplicated");
                }
                coverageTags.add(coverageTag);
            }
            dependencies.add(new SourceDependency(titleValue.textValue(), coverageTags));
        }
        return List.copyOf(dependencies);
    }

    private static List<VisualQuantityObservation> quantityObservations(JsonNode value, boolean required) {
        if (value == null || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException(
                        "visual teaching catalog must return quantityObservations for every cataloged page");
            }
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("visual teaching quantityObservations are invalid");
        }
        List<VisualQuantityObservation> observations = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("visual teaching quantity observation must be an object");
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(QUANTITY_OBSERVATION_FIELDS)) {
                throw new IllegalArgumentException(
                        "visual teaching quantity observation must contain the exact fields");
            }
            try {
                observations.add(quantityObservation(
                        item, requiredText(item.get("ruleGroupIdentifier"), "ruleGroupIdentifier", false)));
            } catch (IllegalArgumentException invalidObservation) {
                throw new IllegalArgumentException(
                        "visual teaching quantity observation is invalid: " + invalidObservation.getMessage(),
                        invalidObservation);
            }
        }
        return List.copyOf(observations);
    }

    private static List<VisualQuantityObservation> boundQuantityObservations(
            JsonNode value, boolean required, List<RuleGroupFact> ruleGroups) {
        if (value == null || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException(
                        "visual teaching catalog must return quantityObservations for every cataloged page");
            }
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("visual teaching quantityObservations are invalid");
        }
        List<VisualQuantityObservation> observations = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("visual teaching quantity observation must be an object");
            }
            Set<String> fields = new java.util.LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(BOUND_QUANTITY_OBSERVATION_FIELDS)) {
                throw new IllegalArgumentException(
                        "visual teaching bound quantity observation must contain the exact fields");
            }
            try {
                int ruleGroupIndex = requiredInteger(item.get("ruleGroupIndex"), "ruleGroupIndex");
                if (ruleGroupIndex < 0 || ruleGroupIndex >= ruleGroups.size()) {
                    throw new IllegalArgumentException("ruleGroupIndex must identify one ruleGroups item");
                }
                observations.add(quantityObservation(item, ruleGroups.get(ruleGroupIndex).identifier()));
            } catch (IllegalArgumentException invalidObservation) {
                throw new IllegalArgumentException(
                        "visual teaching quantity observation is invalid: " + invalidObservation.getMessage(),
                        invalidObservation);
            }
        }
        return List.copyOf(observations);
    }

    private static List<VisualQuantityObservation> discriminatedBoundQuantityObservations(
            JsonNode value, boolean required, List<RuleGroupFact> ruleGroups) {
        if (value == null || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException(
                        "visual teaching catalog must return quantityObservations for every cataloged page");
            }
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("visual teaching quantityObservations are invalid");
        }
        List<VisualQuantityObservation> observations = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("visual teaching quantity observation must be an object");
            }
            try {
                ModelQuantityKind kind = ModelQuantityKind.valueOf(requiredText(item.get("kind"), "kind", false));
                Set<String> fields = new java.util.LinkedHashSet<>();
                item.fieldNames().forEachRemaining(fields::add);
                Set<String> expectedFields = switch (kind) {
                    case PER_VARIANT_EXACT -> PER_VARIANT_EXACT_QUANTITY_FIELDS;
                    case TOTAL_EXACT -> TOTAL_EXACT_QUANTITY_FIELDS;
                    case REQUIRES_PAGE_INSPECTION -> INSPECTION_QUANTITY_FIELDS;
                };
                if (!fields.equals(expectedFields)) {
                    throw new IllegalArgumentException(
                            "quantity kind " + kind + " must contain only its exact fields " + expectedFields);
                }
                int ruleGroupIndex = requiredInteger(item.get("ruleGroupIndex"), "ruleGroupIndex");
                if (ruleGroupIndex < 0 || ruleGroupIndex >= ruleGroups.size()) {
                    throw new IllegalArgumentException("ruleGroupIndex must identify one ruleGroups item");
                }
                String ruleGroupIdentifier = ruleGroups.get(ruleGroupIndex).identifier();
                int pageNumber = requiredInteger(item.get("pageNumber"), "pageNumber");
                String originalSpan = requiredText(item.get("originalSpan"), "originalSpan", false);
                observations.add(switch (kind) {
                    case PER_VARIANT_EXACT -> perVariantObservation(
                            item, pageNumber, ruleGroupIdentifier, originalSpan);
                    case TOTAL_EXACT -> new VisualQuantityObservation(
                            pageNumber,
                            ruleGroupIdentifier,
                            QuantifierScope.TOTAL,
                            "",
                            null,
                            null,
                            requiredInteger(item.get("total"), "total"),
                            originalSpan,
                            QuantityResolution.EXACT);
                    case REQUIRES_PAGE_INSPECTION -> new VisualQuantityObservation(
                            pageNumber,
                            ruleGroupIdentifier,
                            QuantifierScope.UNRESOLVED,
                            requiredText(item.get("variantAxis"), "variantAxis", true),
                            null,
                            null,
                            null,
                            originalSpan,
                            QuantityResolution.REQUIRES_PAGE_INSPECTION);
                });
            } catch (IllegalArgumentException invalidObservation) {
                throw new IllegalArgumentException(
                        "visual teaching quantity observation is invalid: " + invalidObservation.getMessage(),
                        invalidObservation);
            }
        }
        return List.copyOf(observations);
    }

    private static List<VisualQuantityObservation> literalBoundQuantitySpans(
            JsonNode value, boolean required, List<RuleGroupFact> ruleGroups) {
        if (value == null || value.isMissingNode()) {
            if (required) {
                throw new IllegalArgumentException(
                        "visual teaching catalog must return quantityObservations for every cataloged page");
            }
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("visual teaching quantityObservations are invalid");
        }
        List<VisualQuantityObservation> observations = new java.util.ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("visual teaching literal quantity span must be an object");
            }
            Set<String> fields = new LinkedHashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(LITERAL_QUANTITY_SPAN_FIELDS)) {
                throw new IllegalArgumentException(
                        "visual teaching literal quantity span must contain exactly pageNumber, ruleGroupIndex, and originalSpan");
            }
            int ruleGroupIndex = requiredInteger(item.get("ruleGroupIndex"), "ruleGroupIndex");
            if (ruleGroupIndex < 0 || ruleGroupIndex >= ruleGroups.size()) {
                throw new IllegalArgumentException("ruleGroupIndex must identify one ruleGroups item");
            }
            VisualQuantityObservation observation = new VisualQuantityObservation(
                    requiredInteger(item.get("pageNumber"), "pageNumber"),
                    ruleGroups.get(ruleGroupIndex).identifier(),
                    QuantifierScope.LITERAL_SOURCE_SPAN,
                    "",
                    null,
                    null,
                    null,
                    requiredText(item.get("originalSpan"), "originalSpan", false),
                    QuantityResolution.TRANSCRIBED_SOURCE_SPAN);
            observations.add(observation);
        }
        return List.copyOf(observations);
    }

    private static VisualQuantityObservation perVariantObservation(
            JsonNode item, int pageNumber, String ruleGroupIdentifier, String originalSpan) {
        Integer variantCount = nullableInteger(item.get("variantCount"), "variantCount");
        int perVariantQuantity = requiredInteger(item.get("perVariantQuantity"), "perVariantQuantity");
        Integer derivedTotal = null;
        if (variantCount != null) {
            try {
                derivedTotal = Math.multiplyExact(variantCount, perVariantQuantity);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("visual quantity derived total is outside the safe range", overflow);
            }
        }
        return new VisualQuantityObservation(
                pageNumber,
                ruleGroupIdentifier,
                QuantifierScope.PER_VARIANT,
                requiredText(item.get("variantAxis"), "variantAxis", false),
                variantCount,
                perVariantQuantity,
                derivedTotal,
                originalSpan,
                QuantityResolution.EXACT);
    }

    private static VisualQuantityObservation quantityObservation(JsonNode item, String ruleGroupIdentifier) {
        return new VisualQuantityObservation(
                requiredInteger(item.get("pageNumber"), "pageNumber"),
                ruleGroupIdentifier,
                QuantifierScope.valueOf(requiredText(item.get("quantifierScope"), "quantifierScope", false)),
                requiredText(item.get("variantAxis"), "variantAxis", true),
                nullableInteger(item.get("variantCount"), "variantCount"),
                nullableInteger(item.get("perVariantQuantity"), "perVariantQuantity"),
                nullableInteger(item.get("derivedTotal"), "derivedTotal"),
                requiredText(item.get("originalSpan"), "originalSpan", false),
                QuantityResolution.valueOf(requiredText(item.get("resolution"), "resolution", false)));
    }

    private enum ModelQuantityKind {
        PER_VARIANT_EXACT,
        TOTAL_EXACT,
        REQUIRES_PAGE_INSPECTION
    }

    private static int requiredInteger(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode value, String field) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static Integer nullableInteger(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        return requiredInteger(value, field);
    }

    private static String requiredText(JsonNode value, String field, boolean allowEmpty) {
        if (value == null || !value.isTextual() || (!allowEmpty && value.textValue().isBlank())) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.textValue();
    }

    private static String sourceDependencyFact(SourceDependency dependency) {
        String missing = dependency.missingCoverageTags().isEmpty()
                ? "具体规则"
                : dependency.missingCoverageTags().stream()
                        .map(tag -> switch (tag) {
                            case "setup" -> "开局步骤";
                            case "core_loop" -> "核心回合流程";
                            case "end" -> "结束规则";
                            case "scoring" -> "计分规则";
                            default -> "对应规则";
                        })
                        .collect(java.util.stream.Collectors.joining("、"));
        return "来源可用性：当前页明确指向“" + dependency.title() + "”；当前页本身不提供" + missing + "。";
    }

    static ProgressiveTeachingStartDraft normalizeProgressiveTeachingStartBindings(
            CatalogRequest request,
            ProgressiveTeachingStartDraft draft) {
        if (draft == null) throw new IllegalArgumentException("progressive visual teaching model returned no draft");
        List<Integer> requestedOrder = request.pages().stream().map(PageImageInput::pageNumber).toList();
        List<Integer> returnedOrder = draft.pages().stream().map(TeachingPageSketch::pageNumber).toList();
        if (returnedOrder.size() != requestedOrder.size()
                || Set.copyOf(returnedOrder).size() != returnedOrder.size()
                || !Set.copyOf(returnedOrder).equals(Set.copyOf(requestedOrder))) {
            throw new IllegalArgumentException("progressive visual teaching did not bind every supplied page exactly");
        }
        if (!Set.copyOf(requestedOrder).contains(draft.selectedPageFacts().pageNumber())) {
            throw new IllegalArgumentException("progressive visual teaching selected an unknown supplied page");
        }
        List<TeachingPageSketch> ordered = requestedOrder.stream()
                .map(pageNumber -> draft.pages().stream()
                        .filter(page -> page.pageNumber() == pageNumber)
                        .findFirst()
                        .orElseThrow())
                .toList();
        return new ProgressiveTeachingStartDraft(
                ordered, withoutVisualEnrichment(draft.selectedPageFacts()));
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

    private static java.util.List<String> normalizedStrings(JsonNode value) {
        return stringValues(value).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    /** Optional anchors must never make a page's textual visual ledger unusable. */
    private static java.util.List<VisualAnchor> visualAnchors(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) return java.util.List.of();
        java.util.List<VisualAnchor> anchors = new java.util.ArrayList<>();
        value.forEach(anchor -> {
            if (!anchor.isObject()) return;
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

    /** One malformed optional icon keeps valid page facts, but permanently prevents a false completeness claim. */
    private static ParsedIconInventory iconOccurrences(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) {
            return new ParsedIconInventory(java.util.List.of(), false);
        }
        java.util.List<IconOccurrence> icons = new java.util.ArrayList<>();
        boolean contractValid = true;
        for (JsonNode icon : value) {
            if (!icon.isObject() || icon.size() != 10) {
                contractValid = false;
                continue;
            }
            try {
                IconMeaningStatus status = IconMeaningStatus.valueOf(
                        requiredText(icon.get("meaningStatus"), "meaningStatus", false));
                icons.add(new IconOccurrence(
                        requiredText(icon.get("groupKey"), "groupKey", false),
                        requiredText(icon.get("name"), "name", false),
                        requiredText(icon.get("visualDescription"), "visualDescription", false),
                        requiredText(icon.get("explanation"), "explanation", true),
                        requiredText(icon.get("evidenceText"), "evidenceText", true),
                        status,
                        requiredInteger(icon.get("x"), "x"),
                        requiredInteger(icon.get("y"), "y"),
                        requiredInteger(icon.get("width"), "width"),
                        requiredInteger(icon.get("height"), "height")));
            } catch (IllegalArgumentException invalidIcon) {
                contractValid = false;
            }
        }
        return new ParsedIconInventory(java.util.List.copyOf(icons), contractValid);
    }

    private record ParsedIconInventory(List<IconOccurrence> occurrences, boolean contractValid) {}

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

    /**
     * Multi-page startup responses are deliberately partial-tolerant: exact, unique page bindings are retained and
     * the application retries only absent pages. Unknown or duplicate bindings are never guessed across multiple
     * images. A one-image retry may safely repair its sole returned page number because no cross-page binding exists.
     */
    static CatalogDraft normalizeTeachingPageBindings(CatalogRequest request, CatalogDraft draft) {
        if (draft == null) throw new IllegalArgumentException("visual teaching catalog model returned no draft");
        List<Integer> requestedOrder = request.pages().stream().map(PageImageInput::pageNumber).toList();
        if (requestedOrder.size() == 1 && draft.pages().size() == 1) {
            return new CatalogDraft(List.of(withoutVisualEnrichment(
                    rebound(draft.pages().getFirst(), requestedOrder.getFirst()))));
        }
        Set<Integer> requested = Set.copyOf(requestedOrder);
        Map<Integer, Long> returnedCounts = draft.pages().stream().collect(Collectors.groupingBy(
                PageSummary::pageNumber, Collectors.counting()));
        List<PageSummary> accepted = draft.pages().stream()
                .filter(summary -> requested.contains(summary.pageNumber()))
                .filter(summary -> returnedCounts.get(summary.pageNumber()) == 1)
                .map(SpringAiVisualRulebookPageCatalogModel::withoutVisualEnrichment)
                .sorted(java.util.Comparator.comparingInt(summary -> requestedOrder.indexOf(summary.pageNumber())))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException(
                    "visual teaching catalog returned no safely bound supplied page; requested=" + requested);
        }
        return new CatalogDraft(accepted);
    }

    private static PageSummary withoutVisualEnrichment(PageSummary summary) {
        return new PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                List.of(),
                List.of(),
                false,
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.quantityObservations(),
                summary.ruleGroupFacts());
    }

    private static PageSummary rebound(PageSummary summary, int pageNumber) {
        return new PageSummary(
                pageNumber,
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                summary.visualAnchors(),
                summary.iconOccurrences(),
                summary.iconInventoryComplete(),
                summary.sourceDependencies(),
                summary.ruleGroupIdentifiers(),
                summary.ruleGroupInventoryComplete(),
                summary.quantityObservations().stream()
                        .map(observation -> new VisualQuantityObservation(
                                pageNumber,
                                observation.ruleGroupIdentifier(),
                                observation.quantifierScope(),
                                observation.variantAxis(),
                                observation.variantCount(),
                                observation.perVariantQuantity(),
                                observation.derivedTotal(),
                                observation.originalSpan(),
                                observation.resolution()))
                        .toList(),
                summary.ruleGroupFacts());
    }
}
