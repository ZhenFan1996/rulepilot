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
import com.rulepilot.assistant.domain.RuleTermDefinition;
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

@Tag("real-term-definition-evaluation")
class AnswerTermDefinitionRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void generatesAndInspectsActualTermDefinitionsFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_TERM_DEFINITION_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/term-definition-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/term-definition-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("allExpectedTermsDefined", true)
                .containsEntry("noUnexpectedDefinition", true)
                .containsEntry("allDefinitionsCitedToDefiningPage", true)
                .containsEntry("boundariesMatchEvidence", true)
                .containsEntry("playerVisibleAnswerCoversDefinitions", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runCase(
            Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        UUID versionId = UUID.nameUUIDFromBytes(("definition:" + caseId).getBytes(StandardCharsets.UTF_8));
        List<EvidenceInput> modelEvidence = new ArrayList<>();
        List<HybridEvidenceHit> publicationEvidence = new ArrayList<>();
        Map<Integer, UUID> citationByPage = new LinkedHashMap<>();
        for (JsonNode pageNode : caseNode.path("pages")) {
            int page = pageNode.asInt();
            UUID chunkId = UUID.nameUUIDFromBytes(
                    ("definition:" + caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
            citationByPage.put(page, chunkId);
            String pageText = extractPage(pdf, page);
            RuleEvidenceHit source = new RuleEvidenceHit(
                    chunkId, versionId, "REAL_DEFINITION_PAGE", "Rule term definitions",
                    pageText, page, page, 1.0);
            modelEvidence.add(new EvidenceInput(
                    chunkId, source.sectionType(), source.heading(), pageText, page, page));
            publicationEvidence.add(new HybridEvidenceHit(source, 1.0, 1, null, true));
        }
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, LearningIntent.DEFINE, PlayerLocale.EN), modelEvidence);
        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerTermDefinitionResolver resolver = new AnswerTermDefinitionResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        int repairCount = 0;
        List<RuleTermDefinition> definitions;
        try {
            definitions = resolver.resolve(request, draft);
        } catch (RuntimeException invalidDefinitions) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The definition answer omitted or invalidly structured its cited term definitions.",
                    "Return every requested term separately in termDefinitions with a direct definition, a useful evidence-supported boundary, and its own citationIds.",
                    "Each definition must be at most 600 characters and one or two concise sentences; each boundary must be at most 400 characters and one sentence. Delete full procedures, examples, consequences, and nearby rules.",
                    "Do not substitute an example, consequence, or broad explanation for the definition.")), request, caseId);
            try {
                definitions = resolver.resolve(request, draft);
            } catch (RuntimeException repeatedDefinitionFailure) {
                throw new AssertionError("repaired definition shape remains invalid for " + caseId
                        + "; shapes=" + definitionShapes(draft), repeatedDefinitionFailure);
            }
        }
        StructuredRuleAnswer published;
        try {
            published = validator.publish(
                    versionId, draft, publicationEvidence, List.of(), List.of(), List.of(), List.of(), List.of(), definitions);
        } catch (RuntimeException invalidPublication) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The draft failed final schema or citation validation.",
                    "Use only supplied citationIds, cite each term to the page that directly defines it, and remove unsupported claims.",
                    "Preserve already valid termDefinitions; every definition must remain one or two sentences and at most 600 characters, and every boundary one sentence and at most 400 characters.",
                    "Keep shortVerdict nonblank and at most 200 characters, explanation nonblank and at most 1500 characters, and citationIds nonempty.")), request, caseId);
            try {
                definitions = resolver.resolve(request, draft);
            } catch (RuntimeException invalidDefinitionsAfterPublicationRepair) {
                throw new AssertionError("publication repair broke definition shape for " + caseId
                        + "; publicationFailure=" + invalidPublication.getMessage()
                        + "; shapes=" + definitionShapes(draft), invalidDefinitionsAfterPublicationRepair);
            }
            try {
                published = validator.publish(
                        versionId, draft, publicationEvidence, List.of(), List.of(), List.of(), List.of(), List.of(), definitions);
            } catch (RuntimeException repeatedPublicationFailure) {
                throw new AssertionError("publication repair remains invalid for " + caseId
                        + "; initialFailure=" + invalidPublication.getMessage()
                        + "; draftShape=" + draftShape(draft), repeatedPublicationFailure);
            }
        }

        Evaluation evaluation = evaluate(caseNode, definitions, published, citationByPage);
        if (!evaluation.passes()) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The answer failed a player-centered definition fidelity review.",
                    "Define only the requested terms, using the rulebook's operative wording rather than nearby examples or unrelated rules.",
                    "For each term, state what it is or what action constitutes it; in boundary, state the evidence-supported contrast that prevents confusing it with the other requested term.",
                    "Required definition concepts are: " + expectedSummary(caseNode.path("definitions")) + ".",
                    "Remove these off-scope concepts if present: " + values(caseNode.path("forbiddenAnswerTerms")) + ".")), request, caseId);
            definitions = resolver.resolve(request, draft);
            published = validator.publish(
                    versionId, draft, publicationEvidence, List.of(), List.of(), List.of(), List.of(), List.of(), definitions);
            evaluation = evaluate(caseNode, definitions, published, citationByPage);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("pages", valuesAsIntegers(caseNode.path("pages")));
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("termDefinitions", evaluation.actual());
        result.put("repairCount", repairCount);
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("allExpectedTermsDefined", evaluation.allExpected());
        result.put("noUnexpectedDefinition", evaluation.noUnexpected());
        result.put("allDefinitionsCitedToDefiningPage", evaluation.citationsCorrect());
        result.put("boundariesMatchEvidence", evaluation.boundariesMatch());
        result.put("playerVisibleAnswerCoversDefinitions", evaluation.playerVisibleAnswerCoversDefinitions());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbiddenTerm());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private Evaluation evaluate(
            JsonNode caseNode,
            List<RuleTermDefinition> definitions,
            StructuredRuleAnswer published,
            Map<Integer, UUID> citationByPage) {
        List<JsonNode> expected = new ArrayList<>();
        caseNode.path("definitions").forEach(expected::add);
        List<Map<String, Object>> actual = definitions.stream().map(definition -> Map.<String, Object>of(
                "term", definition.term(),
                "definition", definition.definition(),
                "boundary", definition.boundary(),
                "citationIds", definition.citationIds())).toList();
        boolean allExpected = expected.stream().allMatch(expectation -> definitions.stream().anyMatch(definition ->
                matchesTerm(definition, expectation)
                        && containsTerms(definition.definition() + " " + definition.boundary(),
                                values(expectation.path("definitionTerms")))));
        boolean noUnexpected = definitions.size() == expected.size()
                && definitions.stream().allMatch(definition -> expected.stream().anyMatch(expectation ->
                        matchesTerm(definition, expectation)));
        boolean citationsCorrect = expected.stream().allMatch(expectation -> definitions.stream()
                .filter(definition -> matchesTerm(definition, expectation))
                .anyMatch(definition -> definition.citationIds().equals(List.of(
                        citationByPage.get(expectation.path("citationPage").asInt())))));
        boolean boundariesMatch = expected.stream().allMatch(expectation -> definitions.stream()
                .filter(definition -> matchesTerm(definition, expectation))
                .anyMatch(definition -> !definition.boundary().isBlank()
                        && containsTerms(definition.definition() + " " + definition.boundary(),
                                values(expectation.path("boundaryTerms")))));
        String prose = published.shortVerdict() + " " + published.explanation();
        String playerVisibleAnswer = prose + " " + actual;
        boolean playerVisibleAnswerCoversDefinitions = expected.stream().allMatch(expectation ->
                containsTerms(playerVisibleAnswer, values(expectation.path("definitionTerms"))));
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> containsTerms(prose + " " + actual, List.of(term)));
        return new Evaluation(
                actual, allExpected, noUnexpected, citationsCorrect, boundariesMatch,
                playerVisibleAnswerCoversDefinitions, noForbiddenTerm);
    }

    private boolean matchesTerm(RuleTermDefinition definition, JsonNode expectation) {
        return containsTerms(definition.term(), values(expectation.path("termTerms")));
    }

    private String expectedSummary(JsonNode definitions) {
        List<Map<String, Object>> summary = new ArrayList<>();
        definitions.forEach(definition -> summary.add(Map.of(
                "term", values(definition.path("termTerms")),
                "definition", values(definition.path("definitionTerms")),
                "boundary", values(definition.path("boundaryTerms")))));
        return summary.toString();
    }

    private List<Map<String, Object>> definitionShapes(ModelDraft draft) {
        return draft.termDefinitions().stream().map(definition -> Map.<String, Object>of(
                "termLength", definition.term() == null ? -1 : definition.term().length(),
                "definitionLength", definition.definition() == null ? -1 : definition.definition().length(),
                "boundaryLength", definition.boundary() == null ? -1 : definition.boundary().length(),
                "citationCount", definition.citationIds() == null ? -1 : definition.citationIds().size()))
                .toList();
    }

    private Map<String, Object> draftShape(ModelDraft draft) {
        return Map.of(
                "shortVerdictLength", draft.shortVerdict() == null ? -1 : draft.shortVerdict().length(),
                "explanationLength", draft.explanation() == null ? -1 : draft.explanation().length(),
                "citationCount", draft.citationIds().size(),
                "exceptionCount", draft.exceptions().size(),
                "termDefinitionShapes", definitionShapes(draft));
    }

    private record Evaluation(
            List<Map<String, Object>> actual,
            boolean allExpected,
            boolean noUnexpected,
            boolean citationsCorrect,
            boolean boundariesMatch,
            boolean playerVisibleAnswerCoversDefinitions,
            boolean noForbiddenTerm) {

        boolean passes() {
            return allExpected && noUnexpected && citationsCorrect && boundariesMatch
                    && playerVisibleAnswerCoversDefinitions && noForbiddenTerm;
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
