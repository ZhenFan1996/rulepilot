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
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.ModelExecutionIdentity;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.RuleGroupFact;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.SourceDependency;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogContractViolation;
import com.rulepilot.teaching.VisualRulebookPageCatalogModel.TeachingCatalogRepairCode;
import com.rulepilot.teaching.TeachingOutlineModel.PageImageInput;
import com.rulepilot.teaching.VisualQuantityObservation;
import com.rulepilot.teaching.VisualQuantityObservation.QuantityResolution;
import com.rulepilot.teaching.VisualQuantityObservation.QuantifierScope;
import com.rulepilot.teaching.VisualSourceRuleGroupLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String QWEN_TEACHING_STARTUP_MODEL = "qwen3-vl-flash";
    private static final Set<String> RULE_GROUP_FIELDS = Set.of("identifier", "fact");
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
    private static final int MAX_PRINTED_TERMS = 12;
    private static final int MAX_KEYWORDS = 16;
    private static final int OPTIONAL_METADATA_ABUSE_ITEM_LIMIT = 64;
    private static final int OPTIONAL_METADATA_ITEM_CHARACTER_LIMIT = 512;
    private static final int OPTIONAL_METADATA_TOTAL_CHARACTER_LIMIT = 8_192;
    private final RuntimeModelConfiguration models;
    private final FakeVisualRulebookPageCatalogModel fake;
    private final TeachingOutlineImagePreparer images = new TeachingOutlineImagePreparer();
    private final String teachingStartupPrompt;
    private final int maxCompletionTokens;

    public SpringAiVisualRulebookPageCatalogModel(
            RuntimeModelConfiguration models,
            FakeVisualRulebookPageCatalogModel fake,
            @Value("classpath:prompts/visual-page-teaching-catalog-v6-literal-quantity-spans-system.txt")
                    Resource teachingStartupPrompt,
            @Value("${rulepilot.visual.catalog-max-output-tokens:4800}") int maxCompletionTokens)
            throws IOException {
        this.models = models;
        this.fake = fake;
        this.teachingStartupPrompt = teachingStartupPrompt.getContentAsString(StandardCharsets.UTF_8).strip();
        if (this.teachingStartupPrompt.isBlank()) {
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
        return summarizeForTeaching(request);
    }

    @Override
    public CatalogDraft summarizeForTeaching(CatalogRequest request) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return fake.summarizeForTeaching(request);
        }
        // Catalog orchestration owns page splitting and retry. One audited model call must issue one provider request.
        return normalizeTeachingPageBindings(request, summarizeTeachingOnce(request, owner, ""));
    }

    @Override
    public CatalogDraft repairTeachingCatalog(CatalogRequest request, TeachingCatalogRepairCode repairCode) {
        String owner = request.modelConfigurationOwner();
        if (models.usesFake(Role.VISUAL, owner) || !models.supportsVision(Role.VISUAL, owner)) {
            return VisualRulebookPageCatalogModel.super.repairTeachingCatalog(request, repairCode);
        }
        return normalizeTeachingPageBindings(
                request, summarizeTeachingOnce(request, owner, teachingCatalogRepairInstruction(repairCode)));
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
                                    Read literal text and page layout only from the attached page. If a literal value
                                    is absent or cannot be read reliably, omit it and return
                                    ruleGroupInventoryComplete=false instead of guessing or calculating it.
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
                                    .collect(Collectors.joining("; ")));
                    request.pages().stream().map(images::prepare).forEach(page -> user.media(
                            MimeTypeUtils.parseMimeType(page.mediaType()), new ByteArrayResource(page.content())));
                })
                .call()
                .content();
        return parseTeachingCatalogV6(content);
    }

    private static String teachingCatalogRepairInstruction(TeachingCatalogRepairCode repairCode) {
        if (repairCode == null) throw new IllegalArgumentException("Teaching catalog repair code is required");
        return switch (repairCode) {
            case MALFORMED_JSON ->
                "Return exactly one syntactically valid JSON object and no prose, Markdown, prefix, or suffix.";
            case SCHEMA_MISMATCH ->
                "Return exactly the declared root, page, rule-group, dependency, and quantity-span fields with the declared JSON types and bounds; omit every undeclared field.";
            case DUPLICATE_RULE_GROUP ->
                "Within one page, emit each exact identifier-and-fact tuple at most once. The same identifier may appear more than once only when its facts are different.";
            case PAGE_BINDING_MISMATCH ->
                "Return exactly one pages item for the supplied PDF page and copy its supplied pageNumber exactly; do not add, omit, duplicate, or renumber a page.";
        };
    }

    static OpenAiChatOptions.Builder qwenJsonOptions(String modelName, int maxTokens) {
        return OpenAiChatOptions.builder()
                .model(modelName)
                // Typed page-fact extraction must be replayable. Provider-default sampling
                // made the same page alternate between valid rectangles, malformed JSON, and rejected crops.
                .temperature(0.0)
                .maxTokens(maxTokens)
                .extraBody(Map.of("enable_thinking", false))
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
    }

    static String teachingStartupModelName(String provider, String configuredModel) {
        // Teaching startup is a bounded image-to-typed-facts workload, not the provider's general reasoning role.
        // A paid trace on the same immutable source page established that Qwen's dedicated VL flash model retained
        // the complete typed ledger while removing about four seconds from the semantic call. Keep the override at
        // the request boundary so recommendation, answers, and typed visual page facts still honor
        // their configured model, and expose the resolved name through teachingStartupExecutionIdentity for audit.
        return "qwen".equals(provider) ? QWEN_TEACHING_STARTUP_MODEL : configuredModel;
    }

    static CatalogDraft parseTeachingCatalogV6(String content) {
        try {
            return parseNormalizedTeachingCatalogV6(inlineQuantitySpansAsObservations(content));
        } catch (TeachingCatalogContractViolation violation) {
            throw violation;
        } catch (IllegalArgumentException schemaMismatch) {
            throw new TeachingCatalogContractViolation(
                    TeachingCatalogRepairCode.SCHEMA_MISMATCH, schemaMismatch);
        }
    }

    private static String inlineQuantitySpansAsObservations(String content) {
        if (content == null || content.isBlank()) {
            throw new TeachingCatalogContractViolation(
                    TeachingCatalogRepairCode.MALFORMED_JSON,
                    new IllegalArgumentException("visual page catalog model returned no content"));
        }
        String json = content.strip();
        try {
            ObjectNode root = retainDeclaredObjectFields(
                    JSON.readTree(json), TEACHING_V6_ROOT_FIELDS, "visual teaching catalog root");
            JsonNode pages = root.get("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                throw new IllegalArgumentException("visual teaching catalog must return a non-empty pages array");
            }
            for (JsonNode page : pages) {
                if (!(page instanceof ObjectNode objectPage)) {
                    throw new IllegalArgumentException("visual teaching catalog page must be an object");
                }
                retainDeclaredObjectFields(page, TEACHING_V6_PAGE_FIELDS, "visual teaching catalog page");
                int pageNumber = requiredInteger(page.get("pageNumber"), "pageNumber");
                if (pageNumber < 1) {
                    throw new IllegalArgumentException("visual teaching catalog pageNumber must be positive");
                }
                // These fields are non-authoritative retrieval metadata. Normalize them locally so a valid typed
                // rule ledger is not discarded (and paid for again) because the model returned a few extra labels.
                // The absolute input limits still reject abusive payloads before they reach persistence or search.
                objectPage.set(
                        "printedTerms",
                        boundedOptionalMetadata(page.get("printedTerms"), "printedTerms", MAX_PRINTED_TERMS));
                objectPage.set("keywords", boundedOptionalMetadata(page.get("keywords"), "keywords", MAX_KEYWORDS));
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
                    retainDeclaredObjectFields(
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
            throw new TeachingCatalogContractViolation(
                    TeachingCatalogRepairCode.MALFORMED_JSON,
                    new IllegalArgumentException("visual page catalog model returned invalid JSON", invalidJson));
        }
    }

    private static void strictExternalDocumentDependencies(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 4) {
            throw new IllegalArgumentException(
                    "visual teaching catalog externalDocumentDependencies must be an array of at most four items");
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (JsonNode dependency : value) {
            retainDeclaredObjectFields(
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

    private static ArrayNode boundedOptionalMetadata(JsonNode value, String field, int retainedItems) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        if (value.size() > OPTIONAL_METADATA_ABUSE_ITEM_LIMIT) {
            throw new IllegalArgumentException(field + " exceeds the absolute metadata item limit");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        int characters = 0;
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank text");
            }
            String text = item.textValue().strip();
            characters = Math.addExact(characters, text.length());
            if (text.length() > OPTIONAL_METADATA_ITEM_CHARACTER_LIMIT
                    || characters > OPTIONAL_METADATA_TOTAL_CHARACTER_LIMIT) {
                throw new IllegalArgumentException(field + " exceeds the absolute metadata character limit");
            }
            distinct.add(text);
        }
        ArrayNode bounded = JSON.createArrayNode();
        distinct.stream().limit(retainedItems).forEach(bounded::add);
        return bounded;
    }

    private static ObjectNode retainDeclaredObjectFields(
            JsonNode value, Set<String> expected, String contract) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(contract + " must be an object");
        }
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(expected)) {
            throw new IllegalArgumentException(contract + " must contain the declared fields " + expected);
        }
        object.retain(expected);
        return object;
    }

    private static CatalogDraft parseNormalizedTeachingCatalogV6(String content) {
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
                JsonNode sourceDependencyInventory = page.get("externalDocumentDependencies");
                if (sourceDependencyInventory == null || !sourceDependencyInventory.isArray()) {
                    throw new IllegalArgumentException(
                            "visual teaching catalog must return externalDocumentDependencies for every page");
                }
                List<SourceDependency> dependencies = sourceDependencies(sourceDependencyInventory, "documentTitle");
                int pageNumber = page.path("pageNumber").asInt();
                List<RuleGroupFact> boundRuleGroups = strictBoundRuleGroups(pageNumber, page.get("ruleGroups"));
                JsonNode ruleGroupCompleteness = page.get("ruleGroupInventoryComplete");
                if (ruleGroupCompleteness == null || !ruleGroupCompleteness.isBoolean()) {
                    throw new IllegalArgumentException(
                            "visual teaching catalog must return ruleGroupInventoryComplete for every page");
                }
                List<String> ruleGroupIdentifiers =
                        boundRuleGroups.stream().map(RuleGroupFact::identifier).toList();
                boolean ruleGroupInventoryComplete = ruleGroupCompleteness.booleanValue();
                List<VisualQuantityObservation> quantityObservations =
                        literalBoundQuantitySpans(page.get("quantityObservations"), true, boundRuleGroups);
                String printedTerms = joinedText(page.get("printedTerms"), "; ");
                String factualSummary = joinedText(page.get("factualSummary"), "\n");
                if (!boundRuleGroups.isEmpty()) {
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
                summaries.add(new PageSummary(
                        pageNumber,
                        printedTerms,
                        factualSummary,
                        normalizedStrings(page.get("keywords")),
                        List.of(),
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
            if (!fields.equals(RULE_GROUP_FIELDS)) {
                throw new IllegalArgumentException(
                        "visual teaching catalog ruleGroups item must contain exactly identifier and fact");
            }
            String label = requiredText(item.get("identifier"), "identifier", false).strip();
            String fact = requiredText(item.get("fact"), "fact", false).strip();
            String exactGroup = VisualSourceRuleGroupLedger.identity(label)
                    + "\u0000"
                    + VisualSourceRuleGroupLedger.identity(fact);
            if (!exactGroups.add(exactGroup)) {
                throw new TeachingCatalogContractViolation(
                        TeachingCatalogRepairCode.DUPLICATE_RULE_GROUP,
                        new IllegalArgumentException(
                                "visual teaching catalog contains an exactly duplicated rule group"));
            }
            if (fact.indexOf('\n') >= 0 || fact.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("visual teaching catalog rule-group fact must be a single line");
            }
            groups.add(new RuleGroupFact(
                    "page-" + pageNumber + "-group-" + (groups.size() + 1), label, fact));
        }
        return List.copyOf(groups);
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

    private static int requiredInteger(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
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

    /** Teaching responses must preserve every supplied page binding exactly; repair is owned by the typed caller. */
    static CatalogDraft normalizeTeachingPageBindings(CatalogRequest request, CatalogDraft draft) {
        if (draft == null) {
            throw new TeachingCatalogContractViolation(
                    TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH,
                    new IllegalArgumentException("visual teaching catalog model returned no draft"));
        }
        List<Integer> requestedOrder = request.pages().stream().map(PageImageInput::pageNumber).toList();
        Set<Integer> requested = Set.copyOf(requestedOrder);
        Map<Integer, Long> returnedCounts = draft.pages().stream().collect(Collectors.groupingBy(
                PageSummary::pageNumber, Collectors.counting()));
        boolean exactBindings = draft.pages().size() == requestedOrder.size()
                && returnedCounts.size() == requested.size()
                && returnedCounts.entrySet().stream()
                        .allMatch(entry -> requested.contains(entry.getKey()) && entry.getValue() == 1);
        if (!exactBindings) {
            throw new TeachingCatalogContractViolation(
                    TeachingCatalogRepairCode.PAGE_BINDING_MISMATCH,
                    new IllegalArgumentException(
                            "visual teaching catalog returned no safely bound supplied page; requested=" + requested));
        }
        List<PageSummary> accepted = draft.pages().stream()
                .map(SpringAiVisualRulebookPageCatalogModel::withoutVisualEnrichment)
                .sorted(java.util.Comparator.comparingInt(summary -> requestedOrder.indexOf(summary.pageNumber())))
                .toList();
        return new CatalogDraft(accepted);
    }

    private static PageSummary withoutVisualEnrichment(PageSummary summary) {
        return new PageSummary(
                summary.pageNumber(),
                summary.printedTerms(),
                summary.factualSummary(),
                summary.keywords(),
                List.of(),
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
