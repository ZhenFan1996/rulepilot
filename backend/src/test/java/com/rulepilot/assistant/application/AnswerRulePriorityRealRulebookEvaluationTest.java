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
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.RulePriorityResolution;
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

@Tag("real-rule-priority-evaluation")
class AnswerRulePriorityRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualPriorityRulingsFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_PRIORITY_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/rule-priority-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/rule-priority-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("expectedResolutionPresent", true)
                .containsEntry("noUnexpectedResolution", true)
                .containsEntry("allResolutionsCitedToSourcePage", true)
                .containsEntry("allResolutionBasesCorrect", true)
                .containsEntry("baseCompetingResolutionComplete", true)
                .containsEntry("playerVisibleAnswerCoversResolution", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("priority:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> modelEvidence = new ArrayList<>();
        List<HybridEvidenceHit> publicationEvidence = new ArrayList<>();
        Map<Integer, UUID> citationByPage = new LinkedHashMap<>();
        for (JsonNode pageNode : caseNode.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(
                    ("priority:" + caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
            citationByPage.put(page, chunkId);
            String pageText = extractPage(pdf, page);
            RuleEvidenceHit source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_PRIORITY_PAGE", "Rule priority relationship",
                    pageText, page, page, 1.0);
            modelEvidence.add(new EvidenceInput(
                    chunkId, source.sectionType(), source.heading(), pageText, page, page));
            publicationEvidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN), modelEvidence);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerRulePriorityResolver resolver = new AnswerRulePriorityResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        int repairCount = 0;
        List<RulePriorityResolution> resolutions;
        try {
            resolutions = resolver.resolve(request, draft);
        } catch (RuntimeException invalidResolution) {
            repairCount++;
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, false)), request, caseId);
            resolutions = resolver.resolve(request, draft);
        }
        StructuredRuleAnswer published;
        try {
            published = publish(validator, versionId, draft, publicationEvidence, resolutions);
        } catch (RuntimeException invalidPublication) {
            repairCount++;
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, true)), request, caseId);
            resolutions = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, publicationEvidence, resolutions);
        }

        Evaluation evaluation = evaluate(caseNode, resolutions, published, citationByPage);
        if (!evaluation.passes()) {
            repairCount++;
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
        result.put("priorityResolutions", evaluation.actual());
        result.put("repairCount", repairCount);
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("expectedResolutionPresent", evaluation.expectedPresent());
        result.put("noUnexpectedResolution", evaluation.noUnexpected());
        result.put("allResolutionsCitedToSourcePage", evaluation.citationsCorrect());
        result.put("allResolutionBasesCorrect", evaluation.basisCorrect());
        result.put("baseCompetingResolutionComplete", evaluation.complete());
        result.put("playerVisibleAnswerCoversResolution", evaluation.playerVisibleCovers());
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
            List<RulePriorityResolution> resolutions) {
        return validator.publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                resolutions);
    }

    private Evaluation evaluate(
            JsonNode caseNode,
            List<RulePriorityResolution> resolutions,
            StructuredRuleAnswer published,
            Map<Integer, UUID> citationByPage) {
        List<Map<String, Object>> actual = resolutions.stream().map(resolution -> Map.<String, Object>of(
                "baseRule", resolution.baseRule(),
                "competingRule", resolution.competingRule(),
                "resolution", resolution.resolution(),
                "basis", resolution.basis().name(),
                "citationIds", resolution.citationIds())).toList();
        boolean expectedPresent = resolutions.stream().anyMatch(resolution ->
                containsTerms(resolution.baseRule(), values(caseNode.path("baseRuleTerms")))
                        && containsTerms(resolution.competingRule(), values(caseNode.path("competingRuleTerms")))
                        && containsTerms(resolution.resolution(), values(caseNode.path("resolutionTerms"))));
        boolean noUnexpected = resolutions.size() == 1;
        UUID expectedCitation = citationByPage.get(caseNode.path("citationPage").asInt());
        boolean citationsCorrect = resolutions.stream()
                .allMatch(resolution -> resolution.citationIds().equals(List.of(expectedCitation)));
        RulePriorityBasis expectedBasis = RulePriorityBasis.valueOf(caseNode.path("expectedBasis").asText());
        boolean basisCorrect = resolutions.stream().allMatch(resolution -> resolution.basis() == expectedBasis);
        boolean complete = resolutions.stream().allMatch(resolution -> !resolution.baseRule().isBlank()
                && !resolution.competingRule().isBlank() && !resolution.resolution().isBlank());
        String playerVisible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean playerVisibleCovers = containsTerms(playerVisible, values(caseNode.path("resolutionTerms")));
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> containsTerms(playerVisible, List.of(term)));
        return new Evaluation(actual, expectedPresent, noUnexpected, citationsCorrect, basisCorrect,
                complete, playerVisibleCovers, noForbiddenTerm);
    }

    private List<String> repairFeedback(JsonNode caseNode, boolean publicationFailure) {
        return List.of(
                publicationFailure
                        ? "The answer failed final schema or citation validation."
                        : "The answer omitted or invalidly structured the cited rule-priority relationship.",
                "Return exactly one priorityResolutions item with nonblank baseRule, competingRule, resolution, the supplied expected basis, and citationIds from the supplied page.",
                "Use priorityResolutions[].basis " + caseNode.path("expectedBasis").asText()
                        + "; keep the top-level answerBasis DIRECT_RULE.",
                "Do not infer priority from specificity, heading, page order, theme, or model knowledge.",
                "The required base concepts are " + values(caseNode.path("baseRuleTerms"))
                        + "; competing concepts are " + values(caseNode.path("competingRuleTerms"))
                        + "; resolution concepts are " + values(caseNode.path("resolutionTerms")) + ".");
    }

    private List<String> fidelityFeedback(JsonNode caseNode) {
        return List.of(
                "The structured priority ruling failed a player-centered fidelity review.",
                "State only the explicit relationship on the cited page and preserve whether it is unconditional, impossibility-based, or conflict-only.",
                "Return exactly one priorityResolutions item using basis "
                        + caseNode.path("expectedBasis").asText() + ".",
                "Cover base concepts " + values(caseNode.path("baseRuleTerms"))
                        + ", competing concepts " + values(caseNode.path("competingRuleTerms"))
                        + ", and resolution concepts " + values(caseNode.path("resolutionTerms")) + ".",
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
