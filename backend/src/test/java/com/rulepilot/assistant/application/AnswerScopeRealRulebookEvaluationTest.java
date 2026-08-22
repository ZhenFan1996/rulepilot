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
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
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

@Tag("real-rule-scope-evaluation")
class AnswerScopeRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualApplicabilityAnswersFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_RULE_SCOPE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/rule-scope-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/rule-scope-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("expectedScopePresent", true)
                .containsEntry("singleScopeRuling", true)
                .containsEntry("scopeCitedToSourcePage", true)
                .containsEntry("scopeStatusCorrect", true)
                .containsEntry("scopeBasisCorrect", true)
                .containsEntry("conditionSituationEffectComplete", true)
                .containsEntry("playerVisibleAnswerCoversOutcome", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = caseNode.path("page").asInt();
        UUID versionId = UUID.nameUUIDFromBytes(("scope:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("scope:" + caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
        String pageText = extractPage(pdf, page);
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_SCOPE_PAGE", "Rule applicability", pageText, page, page, 1.0);
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.VERIFY, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, source.sectionType(), source.heading(), pageText, page, page)));
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerScopeResolver resolver = new AnswerScopeResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        List<String> repairStages = new ArrayList<>();
        List<RuleScopeResolution> resolutions;
        try {
            resolutions = resolver.resolve(request, draft);
        } catch (RuntimeException invalidResolution) {
            repairStages.add("STRUCTURE_OR_SCOPE");
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, false)), request, caseId);
            try {
                resolutions = resolver.resolve(request, draft);
            } catch (RuntimeException invalidRepair) {
                throw new IllegalArgumentException(
                        "scope repair remained invalid for " + caseId + ": " + draft.scopeResolutions(),
                        invalidRepair);
            }
        }
        StructuredRuleAnswer published;
        try {
            published = publish(validator, versionId, draft, evidence, resolutions);
        } catch (RuntimeException invalidPublication) {
            repairStages.add("PUBLICATION");
            draft = prepared(model.revise(request, draft, repairFeedback(caseNode, true)), request, caseId);
            resolutions = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, evidence, resolutions);
        }
        Evaluation evaluation = evaluate(caseNode, resolutions, published, chunkId);
        if (!evaluation.passes()) {
            repairStages.add("SEMANTIC_FIDELITY");
            draft = prepared(model.revise(request, draft, fidelityFeedback(caseNode)), request, caseId);
            resolutions = resolver.resolve(request, draft);
            published = publish(validator, versionId, draft, evidence, resolutions);
            evaluation = evaluate(caseNode, resolutions, published, chunkId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("page", page);
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("scopeResolutions", evaluation.actual());
        result.put("repairStages", List.copyOf(repairStages));
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("expectedScopePresent", evaluation.expectedPresent());
        result.put("singleScopeRuling", evaluation.singleResolution());
        result.put("scopeCitedToSourcePage", evaluation.citationCorrect());
        result.put("scopeStatusCorrect", evaluation.statusCorrect());
        result.put("scopeBasisCorrect", evaluation.basisCorrect());
        result.put("conditionSituationEffectComplete", evaluation.complete());
        result.put("playerVisibleAnswerCoversOutcome", evaluation.playerVisibleCovers());
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
            List<RuleScopeResolution> resolutions) {
        return validator.publish(
                versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), resolutions);
    }

    private Evaluation evaluate(
            JsonNode caseNode,
            List<RuleScopeResolution> resolutions,
            StructuredRuleAnswer published,
            UUID expectedCitation) {
        List<Map<String, Object>> actual = resolutions.stream().map(resolution -> Map.<String, Object>of(
                "ruleContext", resolution.ruleContext(),
                "governingCondition", resolution.governingCondition(),
                "currentSituation", resolution.currentSituation(),
                "matchStatus", resolution.matchStatus().name(),
                "effect", resolution.effect(),
                "basis", resolution.basis().name(),
                "citationIds", resolution.citationIds())).toList();
        boolean expectedPresent = !resolutions.isEmpty();
        boolean singleResolution = resolutions.size() == 1;
        boolean citationCorrect = resolutions.stream()
                .allMatch(resolution -> resolution.citationIds().equals(List.of(expectedCitation)));
        ScopeMatchStatus expectedStatus = ScopeMatchStatus.valueOf(caseNode.path("expectedStatus").asText());
        boolean statusCorrect = resolutions.stream().allMatch(resolution -> resolution.matchStatus() == expectedStatus);
        ScopeBasis expectedBasis = ScopeBasis.valueOf(caseNode.path("expectedBasis").asText());
        boolean basisCorrect = resolutions.stream().allMatch(resolution -> resolution.basis() == expectedBasis);
        boolean complete = resolutions.stream().allMatch(resolution ->
                containsTerms(resolution.governingCondition(), values(caseNode.path("conditionTerms")))
                        && containsTerms(resolution.currentSituation(), values(caseNode.path("situationTerms")))
                        && containsTerms(resolution.effect(), values(caseNode.path("effectTerms"))));
        String playerVisible = published.shortVerdict() + " " + published.explanation() + " " + actual;
        boolean playerVisibleCovers = containsTerms(playerVisible, values(caseNode.path("effectTerms")));
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> containsTerms(playerVisible, List.of(term)));
        return new Evaluation(actual, expectedPresent, singleResolution, citationCorrect, statusCorrect,
                basisCorrect, complete, playerVisibleCovers, noForbiddenTerm);
    }

    private List<String> repairFeedback(JsonNode caseNode, boolean publicationFailure) {
        return List.of(
                publicationFailure
                        ? "The answer failed final schema or citation validation."
                        : "The answer omitted or invalidly structured the cited applicability ruling.",
                "Return exactly one scopeResolutions item with ruleContext, governingCondition, currentSituation, matchStatus, effect, basis, and citationIds from the supplied page.",
                "Use matchStatus " + caseNode.path("expectedStatus").asText() + " and basis "
                        + caseNode.path("expectedBasis").asText() + ".",
                "Preserve the cited condition literally, match only setup facts stated in the current question, and do not invent the converse of the rule.",
                "A specific tied-rank reward remains applicable even when an ordinary reward is normally limited to another player count.",
                "For PLAYER_COUNT_EXCEPTION, include both the ordinary player-count clause and the specific tie clause in governingCondition. Never say regardless of player count, any player count, all player counts, or another universal equivalent.",
                "Required condition concepts: " + values(caseNode.path("conditionTerms"))
                        + "; situation concepts: " + values(caseNode.path("situationTerms"))
                        + "; effect concepts: " + values(caseNode.path("effectTerms")) + ".");
    }

    private List<String> fidelityFeedback(JsonNode caseNode) {
        return List.of(
                "The applicability ruling failed a player-centered semantic review.",
                "State the cited condition, the player's stated setup, whether that setup matches, and the exact resulting effect.",
                "Return one scopeResolutions item using matchStatus " + caseNode.path("expectedStatus").asText()
                        + " and basis " + caseNode.path("expectedBasis").asText() + ".",
                "Do not erase a specific exception with a more general player-count rule and do not infer an unstated converse.",
                "Never describe this single exception as applying regardless of player count, at any player count, or for all player counts.",
                "Cover condition concepts " + values(caseNode.path("conditionTerms"))
                        + ", situation concepts " + values(caseNode.path("situationTerms"))
                        + ", and effect concepts " + values(caseNode.path("effectTerms")) + ".",
                "Remove these unsupported outcomes if present: " + values(caseNode.path("forbiddenAnswerTerms")) + ".");
    }

    private record Evaluation(
            List<Map<String, Object>> actual,
            boolean expectedPresent,
            boolean singleResolution,
            boolean citationCorrect,
            boolean statusCorrect,
            boolean basisCorrect,
            boolean complete,
            boolean playerVisibleCovers,
            boolean noForbiddenTerm) {
        boolean passes() {
            return expectedPresent && singleResolution && citationCorrect && statusCorrect && basisCorrect
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
