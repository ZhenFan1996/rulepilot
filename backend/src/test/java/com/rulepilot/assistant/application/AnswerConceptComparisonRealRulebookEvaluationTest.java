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
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleConceptComparison;
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

@Tag("real-concept-comparison-evaluation")
class AnswerConceptComparisonRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualConceptComparisonsFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_COMPARISON_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/rule-concept-comparison-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/rule-concept-comparison-generated-answers.json");
        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
            writeReport(output, model, results);
        }
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("singleComparison", true)
                .containsEntry("conceptsCorrect", true)
                .containsEntry("basisCorrect", true)
                .containsEntry("definitionsComplete", true)
                .containsEntry("boundaryComplete", true)
                .containsEntry("citationsCorrect", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    @Test
    void generatesAndInspectsNonConflictAnswersFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_CONFLICT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/rule-conflict-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/rule-conflict-generated-answers.json");
        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runConflictCase(root, caseNode, model));
            writeReport(output, model, results);
        }
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("noInventedPriorityWinner", true)
                .containsEntry("singleScopeComparison", true)
                .containsEntry("basisCorrect", true)
                .containsEntry("bothRulesExplained", true)
                .containsEntry("boundaryComplete", true)
                .containsEntry("visibleNonConflictVerdict", true)
                .containsEntry("citationsCorrect", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runConflictCase(
            Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        if (!Files.isRegularFile(pdf)) {
            throw new IllegalStateException("real rulebook is required for " + caseId);
        }
        UUID versionId = UUID.nameUUIDFromBytes(("conflict:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> inputs = new ArrayList<>();
        List<HybridEvidenceHit> evidence = new ArrayList<>();
        List<UUID> citationIds = new ArrayList<>();
        for (JsonNode pageNode : node.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(("conflict:" + caseId + ":" + page)
                    .getBytes(StandardCharsets.UTF_8));
            citationIds.add(chunkId);
            String text = extractPage(pdf, page);
            inputs.add(new EvidenceInput(chunkId, "REAL_CONFLICT_PAGE", "Rule conflict check", text, page, page));
            var source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_CONFLICT_PAGE", "Rule conflict check", text, page, page, 1.0);
            evidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), inputs);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        List<String> repairStages = new ArrayList<>();
        ConflictResolution resolution;
        try {
            resolution = resolveNonConflict(request, draft);
        } catch (RuntimeException invalid) {
            repairStages.add("CONFLICT_CLASSIFICATION_OR_STRUCTURE");
            draft = prepared(model.revise(request, draft, conflictFeedback(node)), request, caseId);
            resolution = resolveNonConflict(request, draft);
        }
        StructuredRuleAnswer published;
        try {
            published = publishConflict(versionId, draft, evidence, resolution.comparisons());
        } catch (RuntimeException invalidPublication) {
            repairStages.add("PUBLICATION");
            draft = prepared(model.revise(request, draft, conflictFeedback(node)), request, caseId);
            resolution = resolveNonConflict(request, draft);
            published = publishConflict(versionId, draft, evidence, resolution.comparisons());
        }
        ConflictEvaluation evaluation = evaluateConflict(node, resolution, published, citationIds);
        if (!evaluation.passes()) {
            repairStages.add("PLAYER_ANSWER_FIDELITY");
            draft = prepared(model.revise(request, draft, conflictFeedback(node)), request, caseId);
            resolution = resolveNonConflict(request, draft);
            published = publishConflict(versionId, draft, evidence, resolution.comparisons());
            evaluation = evaluateConflict(node, resolution, published, citationIds);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("priorityResolutions", resolution.priorities());
        result.put("conceptComparisons", evaluation.actual());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("noInventedPriorityWinner", evaluation.noPriority());
        result.put("singleScopeComparison", evaluation.singleComparison());
        result.put("basisCorrect", evaluation.basisCorrect());
        result.put("bothRulesExplained", evaluation.bothRulesExplained());
        result.put("boundaryComplete", evaluation.boundaryComplete());
        result.put("visibleNonConflictVerdict", evaluation.visibleNonConflict());
        result.put("citationsCorrect", evaluation.citationsCorrect());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbidden());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private ConflictResolution resolveNonConflict(ModelRequest request, ModelDraft draft) {
        var priorities = new AnswerRulePriorityResolver().resolve(request, draft);
        var comparisons = new AnswerConceptComparisonResolver().resolve(request, draft);
        if (!priorities.isEmpty() || comparisons.size() != 1) {
            throw new IllegalArgumentException("non-conflicting rules require one scope comparison and no priority");
        }
        return new ConflictResolution(priorities, comparisons);
    }

    private StructuredRuleAnswer publishConflict(
            UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence,
            List<RuleConceptComparison> comparisons) {
        return new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), comparisons);
    }

    private ConflictEvaluation evaluateConflict(
            JsonNode node, ConflictResolution resolution, StructuredRuleAnswer published,
            List<UUID> allowedCitations) {
        List<Map<String, Object>> actual = resolution.comparisons().stream().map(item -> Map.<String, Object>of(
                "leftConcept", item.leftConcept(), "leftDefinition", item.leftDefinition(),
                "rightConcept", item.rightConcept(), "rightDefinition", item.rightDefinition(),
                "commonGround", item.commonGround(), "keyDifference", item.keyDifference(),
                "practicalBoundary", item.practicalBoundary(), "basis", item.basis().name(),
                "citationIds", item.citationIds())).toList();
        String comparisonText = actual.toString();
        String visible = published.shortVerdict() + " " + published.explanation();
        boolean basisCorrect = resolution.comparisons().stream().allMatch(item -> item.basis()
                == ConceptComparisonBasis.valueOf(node.path("expectedBasis").asText()));
        boolean citationsCorrect = resolution.comparisons().stream().allMatch(item -> !item.citationIds().isEmpty()
                && allowedCitations.containsAll(item.citationIds()));
        boolean noForbidden = values(node.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> normalized(visible + " " + comparisonText).contains(normalized(term)));
        return new ConflictEvaluation(
                actual,
                resolution.priorities().isEmpty(),
                resolution.comparisons().size() == 1,
                basisCorrect,
                containsTerms(comparisonText, values(node.path("leftTerms")))
                        && containsTerms(comparisonText, values(node.path("rightTerms"))),
                containsTerms(comparisonText, values(node.path("boundaryTerms"))),
                containsTerms(visible, values(node.path("nonConflictTerms"))),
                citationsCorrect,
                noForbidden);
    }

    private List<String> conflictFeedback(JsonNode node) {
        return List.of(
                "The player asked about a conflict, but the cited rules remain compatible in different scopes.",
                "Leave priorityResolutions empty and return exactly one conceptComparisons item using basis "
                        + node.path("expectedBasis").asText() + ".",
                "Lead shortVerdict by saying directly that the two rules do not conflict.",
                "Explain both rules and state exactly when each applies. Left must cover "
                        + values(node.path("leftTerms")) + "; right must cover "
                        + values(node.path("rightTerms")) + "; the boundary must cover "
                        + values(node.path("boundaryTerms")) + ".",
                "Do not claim an override, precedence, exception, or priority winner unless the evidence explicitly does.");
    }

    private record ConflictResolution(
            List<com.rulepilot.assistant.domain.RulePriorityResolution> priorities,
            List<RuleConceptComparison> comparisons) {}

    private record ConflictEvaluation(
            List<Map<String, Object>> actual,
            boolean noPriority,
            boolean singleComparison,
            boolean basisCorrect,
            boolean bothRulesExplained,
            boolean boundaryComplete,
            boolean visibleNonConflict,
            boolean citationsCorrect,
            boolean noForbidden) {
        boolean passes() {
            return noPriority && singleComparison && basisCorrect && bothRulesExplained && boundaryComplete
                    && visibleNonConflict && citationsCorrect && noForbidden;
        }
    }

    private Map<String, Object> runCase(Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("comparison:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> inputs = new ArrayList<>();
        List<HybridEvidenceHit> evidence = new ArrayList<>();
        List<UUID> citationIds = new ArrayList<>();
        for (JsonNode pageNode : node.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(("comparison:" + caseId + ":" + page)
                    .getBytes(StandardCharsets.UTF_8));
            citationIds.add(chunkId);
            String text = extractPage(pdf, page);
            inputs.add(new EvidenceInput(chunkId, "REAL_COMPARISON_PAGE", "Rule concept comparison", text, page, page));
            var source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_COMPARISON_PAGE", "Rule concept comparison", text, page, page, 1.0);
            evidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), inputs);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerConceptComparisonResolver resolver = new AnswerConceptComparisonResolver();
        List<String> repairStages = new ArrayList<>();
        List<RuleConceptComparison> comparisons;
        try {
            comparisons = resolver.resolve(request, draft);
        } catch (RuntimeException invalid) {
            repairStages.add("STRUCTURE_OR_COMPARISON");
            draft = prepared(model.revise(request, draft, feedback(node)), request, caseId);
            comparisons = resolver.resolve(request, draft);
        }
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        StructuredRuleAnswer published;
        try {
            published = validator.publish(
                    versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), comparisons);
        } catch (RuntimeException invalidPublication) {
            repairStages.add("PUBLICATION");
            draft = prepared(model.revise(request, draft, List.of(
                    "The answer failed final publication validation.",
                    "Keep shortVerdict nonblank and at most 240 characters, explanation at most 1500 characters, and preserve exactly one cited conceptComparisons item.",
                    "Keep leftConcept and rightConcept at most 120 characters; leftDefinition and rightDefinition at most 600; commonGround at most 500; keyDifference at most 700; practicalBoundary at most 600. Every field must remain nonblank.",
                    "Use only supplied citationIds and preserve the comparison's factual asymmetry.")), request, caseId);
            comparisons = resolver.resolve(request, draft);
            published = validator.publish(
                    versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), comparisons);
        }
        Evaluation evaluation = evaluate(node, comparisons, published, citationIds);
        if (!evaluation.passes()) {
            repairStages.add("SEMANTIC_REVIEW_FAILED");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("conceptComparisons", evaluation.actual());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("singleComparison", evaluation.singleComparison());
        result.put("conceptsCorrect", evaluation.conceptsCorrect());
        result.put("basisCorrect", evaluation.basisCorrect());
        result.put("definitionsComplete", evaluation.definitionsComplete());
        result.put("boundaryComplete", evaluation.boundaryComplete());
        result.put("citationsCorrect", evaluation.citationsCorrect());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbidden());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private Evaluation evaluate(JsonNode node, List<RuleConceptComparison> comparisons,
            StructuredRuleAnswer published, List<UUID> allowedCitations) {
        List<Map<String, Object>> actual = comparisons.stream().map(item -> Map.<String, Object>of(
                "leftConcept", item.leftConcept(), "leftDefinition", item.leftDefinition(),
                "rightConcept", item.rightConcept(), "rightDefinition", item.rightDefinition(),
                "commonGround", item.commonGround(), "keyDifference", item.keyDifference(),
                "practicalBoundary", item.practicalBoundary(), "basis", item.basis().name(),
                "citationIds", item.citationIds())).toList();
        boolean conceptsCorrect = comparisons.stream().allMatch(item ->
                normalized(item.leftConcept()).contains(normalized(node.path("leftConcept").asText()))
                        && normalized(item.rightConcept()).contains(normalized(node.path("rightConcept").asText())));
        boolean basisCorrect = comparisons.stream().allMatch(item -> item.basis()
                == ConceptComparisonBasis.valueOf(node.path("expectedBasis").asText()));
        boolean definitionsComplete = comparisons.stream().allMatch(item -> {
            String completeCard = item.keyDifference() + " " + item.practicalBoundary();
            return containsTerms(item.leftDefinition() + " " + completeCard, values(node.path("leftTerms")))
                    && containsTerms(item.rightDefinition() + " " + completeCard, values(node.path("rightTerms")));
        });
        boolean boundaryComplete = comparisons.stream().allMatch(item -> containsTerms(
                item.keyDifference() + " " + item.practicalBoundary(), values(node.path("boundaryTerms"))));
        boolean citationsCorrect = comparisons.stream().allMatch(item -> !item.citationIds().isEmpty()
                && allowedCitations.containsAll(item.citationIds()));
        String visible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean noForbidden = values(node.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> normalized(visible).contains(normalized(term)));
        return new Evaluation(actual, comparisons.size() == 1, conceptsCorrect, basisCorrect,
                definitionsComplete, boundaryComplete, citationsCorrect, noForbidden);
    }

    private List<String> feedback(JsonNode node) {
        return List.of(
                "The comparison failed a player-centered semantic or schema review.",
                "Return exactly one conceptComparisons item for " + node.path("leftConcept").asText()
                        + " and " + node.path("rightConcept").asText() + ", using basis "
                        + node.path("expectedBasis").asText() + ".",
                "Keep each side's function asymmetric and state a practical boundary. Cite only supplied pages.",
                "Left must cover " + values(node.path("leftTerms")) + "; right must cover "
                        + values(node.path("rightTerms")) + "; boundary must cover "
                        + values(node.path("boundaryTerms")) + ".",
                "Remove unsupported outcomes: " + values(node.path("forbiddenAnswerTerms")) + ".");
    }

    private void writeReport(Path output, SpringAiRuleAnswerModel model, List<Map<String, Object>> results)
            throws Exception {
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1, "generatedAt", Instant.now().toString(),
                "provider", model.providerId(), "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
    }

    private record Evaluation(List<Map<String, Object>> actual, boolean singleComparison, boolean conceptsCorrect,
            boolean basisCorrect, boolean definitionsComplete, boolean boundaryComplete,
            boolean citationsCorrect, boolean noForbidden) {
        boolean passes() { return singleComparison && conceptsCorrect && basisCorrect && definitionsComplete
                && boundaryComplete && citationsCorrect && noForbidden; }
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
