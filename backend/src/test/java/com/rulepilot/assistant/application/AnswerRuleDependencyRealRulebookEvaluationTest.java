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
import com.rulepilot.assistant.adapter.out.model.FakeRuleAnswerModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiRuleAnswerModel;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
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

@Tag("real-rule-dependency-evaluation")
class AnswerRuleDependencyRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsMechanicalWhyAnswersFromRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_DEPENDENCY_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(root.resolve(
                ".local/agent-evaluation/rule-dependency-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/rule-dependency-generated-answers.json");
        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
            writeReport(output, model, results);
        }
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("dependencyCoverageCorrect", true)
                .containsEntry("everyStepUsesRuleOrder", true)
                .containsEntry("everyStepCited", true)
                .containsEntry("containsNoForbiddenPurpose", true)
                .containsEntry("containsNoLogicJargon", true)
                .containsEntry("everyStepAddsExplanation", true)
                .containsEntry("everyStepLocallyCoherent", true)
                .containsEntry("shortVerdictUseful", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(Path root, JsonNode node, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = node.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(node.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = node.path("page").asInt();
        UUID versionId = UUID.nameUUIDFromBytes(("dependency:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("dependency:" + caseId + ":" + page)
                .getBytes(StandardCharsets.UTF_8));
        String excerpt = relevantExcerpt(extractPage(pdf, page), node);
        EvidenceInput input = new EvidenceInput(
                chunkId, "REAL_RULE_PAGE", "Rulebook page " + page, excerpt, page, page);
        var source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_RULE_PAGE", "Rulebook page " + page, excerpt, page, page, 1.0);
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        ModelRequest request = new ModelRequest(
                node.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.WHY, PlayerLocale.EN), List.of(input));

        AnswerWalkthroughResolver resolver = new AnswerWalkthroughResolver();
        List<String> repairStages = new ArrayList<>();
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        List<RuleWalkthroughStep> steps;
        try {
            steps = resolver.resolve(request, draft);
        } catch (RuntimeException invalid) {
            repairStages.add("DEPENDENCY_STRUCTURE");
            draft = prepared(model.revise(request, draft, feedback(node)), request, caseId);
            steps = resolver.resolve(request, draft);
        }
        StructuredRuleAnswer published = publish(versionId, draft, evidence, steps);
        Evaluation evaluation = evaluate(node, steps, published, chunkId);
        if (!evaluation.passes()) {
            repairStages.add("SEMANTIC_REVIEW");
            draft = prepared(model.revise(request, draft, feedback(node)), request, caseId);
            steps = resolver.resolve(request, draft);
            published = publish(versionId, draft, evidence, steps);
            evaluation = evaluate(node, steps, published, chunkId);
        }
        if (!evaluation.passes()) repairStages.add("SEMANTIC_REVIEW_FAILED");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", node.path("question").asText());
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("walkthroughSteps", steps);
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("dependencyCoverageCorrect", evaluation.coverageCorrect());
        result.put("everyStepUsesRuleOrder", evaluation.ruleOrderCorrect());
        result.put("everyStepCited", evaluation.citationsCorrect());
        result.put("containsNoForbiddenPurpose", evaluation.noForbiddenPurpose());
        result.put("containsNoLogicJargon", evaluation.noLogicJargon());
        result.put("everyStepAddsExplanation", evaluation.stepsAddExplanation());
        result.put("everyStepLocallyCoherent", evaluation.stepsLocallyCoherent());
        result.put("shortVerdictUseful", evaluation.shortVerdictUseful());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !steps.isEmpty());
        return Map.copyOf(result);
    }

    private StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleWalkthroughStep> steps) {
        return new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, draft, evidence, List.of(), List.of(), steps);
    }

    private Evaluation evaluate(
            JsonNode node,
            List<RuleWalkthroughStep> steps,
            StructuredRuleAnswer published,
            UUID expectedCitation) {
        String visible = normalized(published.shortVerdict() + " " + published.explanation() + " "
                + steps.stream().map(step -> step.instruction() + " " + step.explanation())
                        .collect(java.util.stream.Collectors.joining(" ")));
        boolean coverage = true;
        for (JsonNode group : node.path("requiredTermGroups")) {
            coverage &= values(group).stream().anyMatch(term -> visible.contains(normalized(term)));
        }
        boolean ruleOrder = steps.size() >= 2
                && steps.stream().allMatch(step -> step.orderBasis() == WalkthroughOrderBasis.RULE_ORDER);
        boolean citations = steps.stream().allMatch(step -> step.citationIds().equals(List.of(expectedCitation)));
        boolean noForbidden = values(node.path("forbiddenPurposeTerms")).stream()
                .noneMatch(term -> visible.contains(normalized(term)));
        boolean noLogicJargon = List.of(
                        "disjunctive", "conjunctive", "antecedent", "consequent",
                        "necessary condition", "sufficient condition", "biconditional")
                .stream().noneMatch(term -> visible.contains(normalized(term)));
        boolean addsExplanation = steps.stream().noneMatch(step -> normalized(step.instruction())
                .equals(normalized(step.explanation())));
        boolean locallyCoherent = steps.stream().allMatch(step -> meaningfulWordCoverage(
                step.instruction(), step.explanation()) >= 0.4);
        String verdict = normalized(published.shortVerdict());
        boolean usefulVerdict = true;
        for (JsonNode group : node.path("requiredVerdictTermGroups")) {
            usefulVerdict &= values(group).stream().anyMatch(term -> verdict.contains(normalized(term)));
        }
        usefulVerdict &= List.of("see below", "see the explanation", "cited explanation", "it depends")
                .stream().noneMatch(term -> verdict.contains(normalized(term)));
        return new Evaluation(
                coverage, ruleOrder, citations, noForbidden, noLogicJargon,
                addsExplanation, locallyCoherent, usefulVerdict);
    }

    private List<String> feedback(JsonNode node) {
        return List.of(
                "The mechanical WHY answer failed its player-centered dependency review.",
                "Return two to six walkthroughSteps using only RULE_ORDER. Each step must cite the supplied page.",
                "The visible answer and steps must cover each semantic group: " + node.path("requiredTermGroups"),
                "The shortVerdict itself must cover each decisive group without redirecting to the explanation: "
                        + node.path("requiredVerdictTermGroups"),
                "Each walkthrough explanation must explain its own instruction and preserve at least 40% of that instruction's meaningful action/object words; do not attach a correct explanation to the wrong step.",
                "Explain the rulebook's prerequisite, transition, and consequence only. Do not add purpose, fairness, balance, theme, pacing, realism, or strategy.",
                "Remove these known ambiguous, over-broad, or unsupported expressions from every visible field: "
                        + node.path("forbiddenPurposeTerms"),
                "Do not reverse permission, requirement, condition, or outcome. Preserve exact numbers and units.");
    }

    private String relevantExcerpt(String page, JsonNode node) {
        String start = node.path("excerptStart").asText();
        String end = node.path("excerptEnd").asText();
        int from = page.indexOf(start);
        if (from < 0) throw new IllegalArgumentException("excerpt start missing: " + start);
        int to = page.indexOf(end, from + start.length());
        if (to < 0) throw new IllegalArgumentException("excerpt end missing: " + end);
        return page.substring(from, Math.min(page.length(), to + end.length())).strip();
    }

    private String extractPage(Path pdf, int page) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
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
                    configuration, new FakeRuleAnswerModel(), context.getBean(VersionedAgentPrompts.class));
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private void writeReport(Path output, SpringAiRuleAnswerModel model, List<Map<String, Object>> results)
            throws Exception {
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
    }

    private List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private double meaningfulWordCoverage(String left, String right) {
        java.util.Set<String> leftWords = java.util.Arrays.stream(normalized(left).split(" "))
                .filter(word -> word.length() >= 4)
                .filter(word -> !java.util.Set.of(
                                "that", "this", "then", "when", "whether", "with", "from", "into", "your",
                                "check", "confirm", "determine", "identify", "proceed", "complete", "perform")
                        .contains(word))
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> rightWords = java.util.Arrays.stream(normalized(right).split(" "))
                .filter(word -> word.length() >= 4)
                .collect(java.util.stream.Collectors.toSet());
        if (leftWords.isEmpty()) return 1.0;
        return (double) leftWords.stream().filter(rightWords::contains).count() / leftWords.size();
    }

    private record Evaluation(
            boolean coverageCorrect,
            boolean ruleOrderCorrect,
            boolean citationsCorrect,
            boolean noForbiddenPurpose,
            boolean noLogicJargon,
            boolean stepsAddExplanation,
            boolean stepsLocallyCoherent,
            boolean shortVerdictUseful) {
        boolean passes() {
            return coverageCorrect && ruleOrderCorrect && citationsCorrect && noForbiddenPurpose
                    && noLogicJargon && stepsAddExplanation && stepsLocallyCoherent && shortVerdictUseful;
        }
    }
}
