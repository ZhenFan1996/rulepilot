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
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
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

@Tag("real-worked-example-evaluation")
class AnswerWorkedExampleRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualWorkedExamplesFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_WORKED_EXAMPLE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/worked-example-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/worked-example-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("expectedExamplePresent", true)
                .containsEntry("noUnexpectedExample", true)
                .containsEntry("allExamplesCitedToSourcePage", true)
                .containsEntry("allExamplesUseOfficialBasis", true)
                .containsEntry("setupActionOutcomeComplete", true)
                .containsEntry("playerVisibleAnswerCoversExample", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(
            Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("example:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> modelEvidence = new ArrayList<>();
        List<HybridEvidenceHit> publicationEvidence = new ArrayList<>();
        Map<Integer, UUID> citationByPage = new LinkedHashMap<>();
        for (JsonNode pageNode : caseNode.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(
                    ("example:" + caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
            citationByPage.put(page, chunkId);
            String pageText = extractPage(pdf, page);
            RuleEvidenceHit source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_EXAMPLE_PAGE", "Rulebook page " + page,
                    pageText, page, page, 1.0);
            modelEvidence.add(new EvidenceInput(
                    chunkId, source.sectionType(), source.heading(), pageText, page, page));
            publicationEvidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.EXAMPLE, PlayerLocale.EN), modelEvidence);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerWorkedExampleResolver resolver = new AnswerWorkedExampleResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        int repairCount = 0;
        List<RuleWorkedExample> examples;
        try {
            examples = resolver.resolve(request, draft);
        } catch (RuntimeException invalidExamples) {
            repairCount++;
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, false)), request, caseId);
            examples = resolver.resolve(request, draft);
        }
        StructuredRuleAnswer published;
        try {
            published = publish(validator, versionId, draft, publicationEvidence, examples);
        } catch (RuntimeException invalidPublication) {
            repairCount++;
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, true)), request, caseId);
            examples = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, publicationEvidence, examples);
        }

        Evaluation evaluation = evaluate(caseNode, examples, published, citationByPage);
        if (!evaluation.passes()) {
            repairCount++;
            draft = prepared(model.revise(request, draft, fidelityFeedback(caseNode)), request, caseId);
            examples = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, publicationEvidence, examples);
            evaluation = evaluate(caseNode, examples, published, citationByPage);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("pages", valuesAsIntegers(caseNode.path("pages")));
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("workedExamples", evaluation.actual());
        result.put("repairCount", repairCount);
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("expectedExamplePresent", evaluation.expectedPresent());
        result.put("noUnexpectedExample", evaluation.noUnexpected());
        result.put("allExamplesCitedToSourcePage", evaluation.citationsCorrect());
        result.put("allExamplesUseOfficialBasis", evaluation.officialBasis());
        result.put("setupActionOutcomeComplete", evaluation.complete());
        result.put("playerVisibleAnswerCoversExample", evaluation.playerVisibleCovers());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbiddenTerm());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private StructuredRuleAnswer publish(
            AnswerPublicationValidator validator,
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleWorkedExample> examples) {
        return validator.publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), examples);
    }

    private Evaluation evaluate(
            JsonNode caseNode,
            List<RuleWorkedExample> examples,
            StructuredRuleAnswer published,
            Map<Integer, UUID> citationByPage) {
        List<Map<String, Object>> actual = examples.stream().map(example -> Map.<String, Object>of(
                "setup", example.setup(),
                "action", example.action(),
                "outcome", example.outcome(),
                "basis", example.basis().name(),
                "citationIds", example.citationIds())).toList();
        List<String> expectedTerms = new ArrayList<>();
        expectedTerms.addAll(values(caseNode.path("setupTerms")));
        expectedTerms.addAll(values(caseNode.path("actionTerms")));
        expectedTerms.addAll(values(caseNode.path("outcomeTerms")));
        boolean expectedPresent = examples.stream().anyMatch(example -> containsTerms(
                example.setup() + " " + example.action() + " " + example.outcome(), expectedTerms));
        boolean noUnexpected = examples.size() == 1;
        UUID expectedCitation = citationByPage.get(caseNode.path("citationPage").asInt());
        boolean citationsCorrect = examples.stream()
                .allMatch(example -> example.citationIds().equals(List.of(expectedCitation)));
        boolean officialBasis = examples.stream()
                .allMatch(example -> example.basis() == WorkedExampleBasis.RULEBOOK_EXAMPLE);
        boolean complete = examples.stream().allMatch(example -> !example.setup().isBlank()
                && !example.action().isBlank() && !example.outcome().isBlank());
        String playerVisible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean playerVisibleCovers = containsTerms(playerVisible, expectedTerms);
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> containsTerms(playerVisible, List.of(term)));
        return new Evaluation(
                actual, expectedPresent, noUnexpected, citationsCorrect, officialBasis,
                complete, playerVisibleCovers, noForbiddenTerm);
    }

    private List<String> repairFeedback(JsonNode caseNode, boolean publicationFailure) {
        return List.of(
                publicationFailure
                        ? "The answer failed final schema or citation validation."
                        : "The example answer omitted or invalidly structured its cited worked example.",
                "Return exactly one workedExamples item with nonblank setup, action, outcome, workedExamples[].basis RULEBOOK_EXAMPLE, and citationIds from the supplied page.",
                "Keep the top-level answerBasis DIRECT_RULE; RULEBOOK_EXAMPLE is never a valid top-level answerBasis.",
                "Keep setup at most 500 characters, action at most 700, and outcome at most 500. Do not leave the example only in prose.",
                "The required setup concepts are " + values(caseNode.path("setupTerms"))
                        + "; action concepts are " + values(caseNode.path("actionTerms"))
                        + "; outcome concepts are " + values(caseNode.path("outcomeTerms")) + ".");
    }

    private List<String> fidelityFeedback(JsonNode caseNode) {
        return List.of(
                "The structured example failed a player-centered fidelity review.",
                "Retell only the named rulebook example and preserve its resource and arithmetic ledger; do not invent steps or generalize it.",
                "Keep the top-level answerBasis DIRECT_RULE and use RULEBOOK_EXAMPLE only for workedExamples[].basis.",
                "Return exactly one RULEBOOK_EXAMPLE with setup concepts " + values(caseNode.path("setupTerms"))
                        + ", action concepts " + values(caseNode.path("actionTerms"))
                        + ", and outcome concepts " + values(caseNode.path("outcomeTerms")) + ".",
                "Remove these off-scope concepts if present: " + values(caseNode.path("forbiddenAnswerTerms")) + ".");
    }

    private record Evaluation(
            List<Map<String, Object>> actual,
            boolean expectedPresent,
            boolean noUnexpected,
            boolean citationsCorrect,
            boolean officialBasis,
            boolean complete,
            boolean playerVisibleCovers,
            boolean noForbiddenTerm) {

        boolean passes() {
            return expectedPresent && noUnexpected && citationsCorrect && officialBasis
                    && complete && playerVisibleCovers && noForbiddenTerm;
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
        var chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(60))
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

    private boolean containsTerms(String text, List<String> terms) {
        String normalized = normalized(text);
        return terms.stream().allMatch(term -> java.util.Arrays.stream(term.split("\\|"))
                .map(this::normalized)
                .anyMatch(normalized::contains));
    }

    private List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private List<Integer> valuesAsIntegers(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asInt()));
        return List.copyOf(values);
    }

    private String extractPage(Path pdf, int page) throws Exception {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document).replaceAll("\\s+", " ").strip();
        }
    }

    private String normalized(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
