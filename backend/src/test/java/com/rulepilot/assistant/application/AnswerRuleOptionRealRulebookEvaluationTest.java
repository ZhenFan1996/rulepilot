package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.adapter.out.model.SpringAiRuleAnswerModel;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("real-rule-option-evaluation")
class AnswerRuleOptionRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsCompleteOptionListsFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_OPTION_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/rule-option-list-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/rule-option-list-generated-answers.json");
        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
            writeReport(output, model, results);
        }
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("optionCountCorrect", true)
                .containsEntry("basisCorrect", true)
                .containsEntry("selectionRuleComplete", true)
                .containsEntry("everyOptionComplete", true)
                .containsEntry("citationsCorrect", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("options:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> inputs = new ArrayList<>();
        List<HybridEvidenceHit> evidence = new ArrayList<>();
        List<UUID> citationIds = new ArrayList<>();
        for (JsonNode pageNode : node.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(("options:" + caseId + ":" + page)
                    .getBytes(StandardCharsets.UTF_8));
            citationIds.add(chunkId);
            String text = relevantExcerpt(extractPage(pdf, page), node);
            inputs.add(new EvidenceInput(chunkId, "REAL_OPTION_PAGE", "Complete rule options", text, page, page));
            var source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_OPTION_PAGE", "Complete rule options", text, page, page, 1.0);
            evidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), inputs);
        AnswerRuleOptionResolver resolver = new AnswerRuleOptionResolver();
        List<String> repairStages = new ArrayList<>();
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        List<RuleOption> options;
        try {
            options = resolver.resolve(request, draft);
        } catch (RuntimeException invalid) {
            repairStages.add("STRUCTURE_OR_OPTIONS");
            draft = prepared(model.revise(request, draft, feedback(node)), request, caseId);
            Files.writeString(
                    root.resolve(".local/agent-evaluation/rule-option-last-repaired-draft.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft) + "\n",
                    StandardCharsets.UTF_8);
            options = resolver.resolve(request, draft);
        }
        StructuredRuleAnswer published;
        try {
            published = publish(versionId, draft, evidence, options);
        } catch (RuntimeException invalidPublication) {
            repairStages.add("PUBLICATION");
            draft = prepared(model.revise(request, draft, List.of(
                    "The answer failed final publication validation.",
                    "Keep shortVerdict nonblank and at most 240 characters and explanation nonblank and at most 1500 characters.",
                    "Preserve exactly " + node.path("expectedCount").asInt() + " complete ruleOptions with the same factual content, basis, option names, conditions, results, and supplied citationIds.",
                    "Keep decisionContext at most 240 characters, selectionRule at most 400, optionName at most 160, availabilityCondition at most 500, and result at most 700.")), request, caseId);
            options = resolver.resolve(request, draft);
            published = publish(versionId, draft, evidence, options);
        }
        Files.writeString(
                root.resolve(".local/agent-evaluation/rule-option-last-candidate.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "caseId", caseId, "draft", draft, "ruleOptions", options)) + "\n",
                StandardCharsets.UTF_8);
        Evaluation evaluation = evaluate(node, options, published, citationIds);
        if (!evaluation.passes()) {
            repairStages.add("SEMANTIC_REVIEW");
            try {
                draft = prepared(model.revise(request, draft, feedback(node)), request, caseId);
                options = resolver.resolve(request, draft);
                try {
                    published = publish(versionId, draft, evidence, options);
                } catch (RuntimeException invalidPublication) {
                    repairStages.add("SEMANTIC_PUBLICATION");
                    draft = prepared(model.revise(request, draft, List.of(
                            "Keep the corrected option facts unchanged, but shorten shortVerdict to at most 240 characters and explanation to at most 1500 characters.",
                            "Preserve every ruleOptions item and supplied citationId.")), request, caseId);
                    options = resolver.resolve(request, draft);
                    published = publish(versionId, draft, evidence, options);
                }
                evaluation = evaluate(node, options, published, citationIds);
            } catch (RuntimeException invalidSemanticRepair) {
                repairStages.add("SEMANTIC_REPAIR_INVALID");
            }
        }
        if (!evaluation.passes()) repairStages.add("SEMANTIC_REVIEW_FAILED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("ruleOptions", evaluation.actual());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("optionCountCorrect", evaluation.optionCountCorrect());
        result.put("basisCorrect", evaluation.basisCorrect());
        result.put("selectionRuleComplete", evaluation.selectionRuleComplete());
        result.put("everyOptionComplete", evaluation.everyOptionComplete());
        result.put("citationsCorrect", evaluation.citationsCorrect());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbidden());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private StructuredRuleAnswer publish(
            UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence, List<RuleOption> options) {
        return new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), options);
    }

    private Evaluation evaluate(
            JsonNode node, List<RuleOption> options, StructuredRuleAnswer published, List<UUID> allowedCitations) {
        List<Map<String, Object>> actual = options.stream().map(item -> Map.<String, Object>of(
                "decisionContext", item.decisionContext(), "selectionRule", item.selectionRule(),
                "optionName", item.optionName(), "availabilityCondition", item.availabilityCondition(),
                "result", item.result(), "basis", item.basis().name(), "citationIds", item.citationIds())).toList();
        boolean countCorrect = options.size() == node.path("expectedCount").asInt();
        boolean basisCorrect = options.stream().allMatch(item -> item.basis()
                == RuleOptionBasis.valueOf(node.path("expectedBasis").asText()));
        boolean selectionComplete = !options.isEmpty() && containsTerms(
                options.getFirst().decisionContext() + " " + options.getFirst().selectionRule(),
                values(node.path("selectionTerms")));
        boolean everyOptionComplete = true;
        for (JsonNode expected : node.path("options")) {
            RuleOption match = options.stream().filter(option -> containsTerms(
                    option.optionName(), values(expected.path("nameTerms")))).findFirst().orElse(null);
            everyOptionComplete &= match != null
                    && containsTerms(match.availabilityCondition(), values(expected.path("availabilityTerms")))
                    && containsTerms(match.result(), values(expected.path("resultTerms")));
        }
        boolean citationsCorrect = options.stream().allMatch(item -> !item.citationIds().isEmpty()
                && allowedCitations.containsAll(item.citationIds()));
        String visible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean noForbidden = values(node.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> normalized(visible).contains(normalized(term)));
        return new Evaluation(actual, countCorrect, basisCorrect, selectionComplete,
                everyOptionComplete, citationsCorrect, noForbidden);
    }

    private List<String> feedback(JsonNode node) {
        return List.of(
                "The complete option list failed a player-centered semantic or schema review.",
                "Return exactly " + node.path("expectedCount").asInt() + " ruleOptions using basis "
                        + node.path("expectedBasis").asText() + ". Repeat one identical decisionContext and selectionRule.",
                "The selection rule must preserve " + values(node.path("selectionTerms")) + ".",
                "For TIMING_CATALOG, every item must use this exact identical selectionRule: Each type may be played only in its stated timing window. Put each type's actual timing only in availabilityCondition and make no frequency claim.",
                "If evidence says must, use the literal word must or required in selectionRule. If it says multiple times, use the literal phrase multiple times or repeat in selectionRule or the affected result.",
                "Return these separately named options with complete availability and result details: "
                        + node.path("options"),
                "Keep mandatory wording, exact costs, only-windows, replacement or no-replacement effects, repeatability, and prohibitions in the structured fields. Cite only supplied pages.",
                "Remove unsupported outcomes: " + values(node.path("forbiddenAnswerTerms")) + ".");
    }

    private void writeReport(Path output, SpringAiRuleAnswerModel model, List<Map<String, Object>> results)
            throws Exception {
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1, "generatedAt", Instant.now().toString(),
                "provider", model.providerId(), "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
    }

    private record Evaluation(
            List<Map<String, Object>> actual,
            boolean optionCountCorrect,
            boolean basisCorrect,
            boolean selectionRuleComplete,
            boolean everyOptionComplete,
            boolean citationsCorrect,
            boolean noForbidden) {
        boolean passes() {
            return optionCountCorrect && basisCorrect && selectionRuleComplete && everyOptionComplete
                    && citationsCorrect && noForbidden;
        }
    }

    private ModelDraft prepared(ModelDraft draft, ModelRequest request, String caseId) {
        var preparation = AnswerDraftPublicationPolicy.prepare(request, draft);
        assertThat(preparation.ready()).as("answer preparation for %s", caseId).isTrue();
        return preparation.draft();
    }

    private SpringAiRuleAnswerModel realAnswerModel() {
        String provider = "deepseek";
        String apiKey = requiredEnvironment("DEEPSEEK_API_KEY");
        String baseUrl = requiredEnvironment("DEEPSEEK_BASE_URL");
        String modelName = requiredEnvironment("DEEPSEEK_MODEL");
        var chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider, apiKey, baseUrl, modelName);
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(Role.ANSWER)).thenReturn(chatModel);
        when(configuration.providerFor(Role.ANSWER)).thenReturn(provider);
        when(configuration.modelNameFor(Role.ANSWER)).thenReturn(modelName);
        when(configuration.usesFake(Role.ANSWER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(Role.ANSWER)).thenReturn(true);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return new SpringAiRuleAnswerModel(
                    configuration, context.getBean(VersionedAgentPrompts.class));
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private String extractPage(Path pdf, int page) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ").strip();
        }
    }

    private String relevantExcerpt(String pageText, JsonNode node) {
        String lower = pageText.toLowerCase(Locale.ROOT);
        int start = 0;
        if (node.hasNonNull("startMarker")) {
            int candidate = lower.indexOf(node.path("startMarker").asText().toLowerCase(Locale.ROOT));
            assertThat(candidate).as("start marker %s", node.path("startMarker").asText()).isGreaterThanOrEqualTo(0);
            start = candidate;
        }
        int end = pageText.length();
        if (node.hasNonNull("endMarker")) {
            int candidate = lower.indexOf(node.path("endMarker").asText().toLowerCase(Locale.ROOT), start + 1);
            assertThat(candidate).as("end marker %s", node.path("endMarker").asText()).isGreaterThan(start);
            end = candidate;
        }
        return pageText.substring(start, end).strip();
    }

    private boolean containsTerms(String text, List<String> terms) {
        String normalized = normalized(text);
        return terms.stream().allMatch(term -> java.util.Arrays.stream(term.split("\\|"))
                .map(this::normalized).anyMatch(normalized::contains));
    }

    private List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
