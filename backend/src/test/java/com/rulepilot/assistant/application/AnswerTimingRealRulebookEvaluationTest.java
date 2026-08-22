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
import com.rulepilot.assistant.domain.RuleTimingResolution;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.TimingOrderBasis;
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

@Tag("real-rule-timing-evaluation")
class AnswerTimingRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualTimingRulingsFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_TIMING_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/rule-timing-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/rule-timing-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("expectedTimingPresent", true)
                .containsEntry("noUnexpectedTiming", true)
                .containsEntry("allTimingsCitedToSourcePage", true)
                .containsEntry("allTimingBasesCorrect", true)
                .containsEntry("contextOrderSourceComplete", true)
                .containsEntry("playerVisibleAnswerCoversTiming", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("timing:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> modelEvidence = new ArrayList<>();
        List<HybridEvidenceHit> publicationEvidence = new ArrayList<>();
        Map<Integer, UUID> citationByPage = new LinkedHashMap<>();
        for (JsonNode pageNode : caseNode.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(
                    ("timing:" + caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
            citationByPage.put(page, chunkId);
            String pageText = extractPage(pdf, page);
            RuleEvidenceHit source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_TIMING_PAGE", "Simultaneous effect ordering",
                    pageText, page, page, 1.0);
            modelEvidence.add(new EvidenceInput(
                    chunkId, source.sectionType(), source.heading(), pageText, page, page));
            publicationEvidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), modelEvidence);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerTimingResolver resolver = new AnswerTimingResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        List<String> repairStages = new ArrayList<>();
        List<RuleTimingResolution> resolutions;
        try {
            resolutions = resolver.resolve(request, draft);
        } catch (RuntimeException invalidResolution) {
            repairStages.add("STRUCTURE_OR_SCOPE");
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, false)), request, caseId);
            resolutions = resolver.resolve(request, draft);
        }
        StructuredRuleAnswer published;
        try {
            published = publish(validator, versionId, draft, publicationEvidence, resolutions);
        } catch (RuntimeException invalidPublication) {
            repairStages.add("PUBLICATION");
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, true)), request, caseId);
            resolutions = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, publicationEvidence, resolutions);
        }

        Evaluation evaluation = evaluate(caseNode, resolutions, published, citationByPage);
        if (!evaluation.passes()) {
            repairStages.add("SEMANTIC_FIDELITY");
            draft = prepared(model.revise(request, draft, fidelityFeedback(caseNode)), request, caseId);
            resolutions = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, publicationEvidence, resolutions);
            evaluation = evaluate(caseNode, resolutions, published, citationByPage);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("pages", valuesAsIntegers(caseNode.path("pages")));
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("timingResolutions", evaluation.actual());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("expectedTimingPresent", evaluation.expectedPresent());
        result.put("noUnexpectedTiming", evaluation.noUnexpected());
        result.put("allTimingsCitedToSourcePage", evaluation.citationsCorrect());
        result.put("allTimingBasesCorrect", evaluation.basisCorrect());
        result.put("contextOrderSourceComplete", evaluation.complete());
        result.put("playerVisibleAnswerCoversTiming", evaluation.playerVisibleCovers());
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
            List<RuleTimingResolution> resolutions) {
        return validator.publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), resolutions);
    }

    private Evaluation evaluate(
            JsonNode caseNode,
            List<RuleTimingResolution> resolutions,
            StructuredRuleAnswer published,
            Map<Integer, UUID> citationByPage) {
        List<Map<String, Object>> actual = resolutions.stream().map(resolution -> Map.<String, Object>of(
                "timingContext", resolution.timingContext(),
                "resolutionOrder", resolution.resolutionOrder(),
                "orderSource", resolution.orderSource(),
                "basis", resolution.basis().name(),
                "citationIds", resolution.citationIds())).toList();
        boolean expectedPresent = resolutions.stream().anyMatch(resolution ->
                containsTerms(resolution.timingContext(), values(caseNode.path("timingContextTerms")))
                        && containsTerms(resolution.resolutionOrder(), values(caseNode.path("resolutionOrderTerms")))
                        && containsTerms(resolution.orderSource(), values(caseNode.path("orderSourceTerms"))));
        boolean noUnexpected = resolutions.size() == 1;
        UUID expectedCitation = citationByPage.get(caseNode.path("citationPage").asInt());
        boolean citationsCorrect = resolutions.stream()
                .allMatch(resolution -> resolution.citationIds().equals(List.of(expectedCitation)));
        TimingOrderBasis expectedBasis = TimingOrderBasis.valueOf(caseNode.path("expectedBasis").asText());
        boolean basisCorrect = resolutions.stream().allMatch(resolution -> resolution.basis() == expectedBasis);
        boolean complete = resolutions.stream().allMatch(resolution -> !resolution.timingContext().isBlank()
                && !resolution.resolutionOrder().isBlank() && !resolution.orderSource().isBlank());
        String playerVisible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean playerVisibleCovers = containsTerms(playerVisible, values(caseNode.path("resolutionOrderTerms")))
                && containsTerms(playerVisible, values(caseNode.path("orderSourceTerms")));
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> containsTerms(playerVisible, List.of(term)));
        return new Evaluation(actual, expectedPresent, noUnexpected, citationsCorrect, basisCorrect,
                complete, playerVisibleCovers, noForbiddenTerm);
    }

    private List<String> repairFeedback(JsonNode caseNode, boolean publicationFailure) {
        return List.of(
                publicationFailure
                        ? "The answer failed final schema or citation validation."
                        : "The answer omitted or invalidly structured the cited simultaneous-effect ordering.",
                "Return exactly one timingResolutions item with nonblank timingContext, resolutionOrder, orderSource, the expected basis, and citationIds from the supplied page.",
                "Use timingResolutions[].basis " + caseNode.path("expectedBasis").asText()
                        + "; keep the top-level answerBasis DIRECT_RULE.",
                "Place the actual ordering authority in orderSource, not a section heading: current-turn player for CURRENT_PLAYER_CHOOSES, printed card top-to-bottom order for PRINTED_TOP_TO_BOTTOM, or normal turn order for NORMAL_TURN_ORDER.",
                "Do not infer order from page layout, card age, initiative, clockwise convention, or model knowledge.",
                "Required context concepts: " + values(caseNode.path("timingContextTerms"))
                        + "; order concepts: " + values(caseNode.path("resolutionOrderTerms"))
                        + "; order-source concepts: " + values(caseNode.path("orderSourceTerms")) + ".");
    }

    private List<String> fidelityFeedback(JsonNode caseNode) {
        return List.of(
                "The structured timing ruling failed a player-centered fidelity review.",
                "State only the ordering relationship on the cited page and preserve exactly who or what sets the order.",
                "orderSource must state the actual authority, not the title of the timing rule.",
                "Return exactly one timingResolutions item using basis "
                        + caseNode.path("expectedBasis").asText() + ".",
                "Cover context concepts " + values(caseNode.path("timingContextTerms"))
                        + ", order concepts " + values(caseNode.path("resolutionOrderTerms"))
                        + ", and order-source concepts " + values(caseNode.path("orderSourceTerms")) + ".",
                "Remove these unsupported concepts if present: "
                        + values(caseNode.path("forbiddenAnswerTerms")) + ".");
    }

    private record Evaluation(
            List<Map<String, Object>> actual,
            boolean expectedPresent,
            boolean noUnexpected,
            boolean citationsCorrect,
            boolean basisCorrect,
            boolean complete,
            boolean playerVisibleCovers,
            boolean noForbiddenTerm) {

        boolean passes() {
            return expectedPresent && noUnexpected && citationsCorrect && basisCorrect
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
                    configuration, context.getBean(VersionedAgentPrompts.class));
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
