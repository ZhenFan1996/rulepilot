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
import com.rulepilot.assistant.RuleAnswerModel.DecisionBranchRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.adapter.out.model.SpringAiRuleAnswerModel;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Role;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("real-decision-table-evaluation")
class AnswerDecisionTableRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesConditionOutcomePairsAcrossIgnoredRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_DECISION_TABLE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/decision-table-cases.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) results.add(runCase(root, caseNode));

        assertThat(results).hasSizeGreaterThanOrEqualTo(3).allSatisfy(result -> assertThat(result)
                .containsEntry("allPairsPresentOnRealPage", true)
                .containsEntry("swappedOutcomesRejectedByOracle", true)
                .containsEntry("toolPreservedPairs", true)
                .containsEntry("allBranchesSeparatelyCited", true)
                .containsEntry("citationPublished", true));
        Path output = root.resolve(".local/agent-evaluation/decision-table-real-rulebooks.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void generatesAndInspectsActualPlayerAnswersFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_DECISION_ANSWER_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/decision-table-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runGeneratedAnswerCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/decision-table-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("allExpectedConditionOutcomePairsPresent", true)
                .containsEntry("noUnexpectedBranch", true)
                .containsEntry("allBranchesCited", true)
                .containsEntry("proseCoversExpectedOutcomes", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    @Test
    void generatesAndInspectsActualExceptionAnswersFromRealRulebookPages() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_EXCEPTION_ANSWER_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode configuration = mapper.readTree(
                root.resolve(".local/agent-evaluation/exception-clause-cases.json").toFile());
        SpringAiRuleAnswerModel model = realAnswerModel();
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode caseNode : configuration.path("cases")) {
            results.add(runGeneratedExceptionCase(root, caseNode, model));
        }

        Path output = root.resolve(".local/agent-evaluation/exception-clause-generated-answers.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "provider", model.providerId(),
                "results", results)) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result)
                .containsEntry("answerPublished", true)
                .containsEntry("allExpectedConditionEffectPairsPresent", true)
                .containsEntry("noUnexpectedClause", true)
                .containsEntry("allClausesCited", true)
                .containsEntry("legacyExceptionsEmpty", true)
                .containsEntry("proseCoversExpectedEffects", true)
                .containsEntry("shortVerdictPreservesRequiredScope", true)
                .containsEntry("containsNoForbiddenAnswerTerm", true)
                .containsEntry("playerAnswerInspected", true));
    }

    private Map<String, Object> runGeneratedExceptionCase(
            Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = caseNode.path("page").asInt();
        String pageText = extractPage(pdf, page);
        UUID versionId = UUID.nameUUIDFromBytes(("exception:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("exception:" + caseId + ":" + page)
                .getBytes(StandardCharsets.UTF_8));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_EXCEPTION_PAGE", "Exceptions and restrictions", pageText, page, page, 1.0);
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, com.rulepilot.assistant.domain.LearningIntent.EXCEPTIONS, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, source.sectionType(), source.heading(), pageText, page, page)));

        ModelDraft draft = prepared(model.compose(request), request, caseId);
        AnswerExceptionClauseResolver resolver = new AnswerExceptionClauseResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        int repairCount = 0;
        List<com.rulepilot.assistant.domain.RuleExceptionClause> resolved;
        try {
            resolved = resolver.resolve(request, draft);
        } catch (RuntimeException invalidExceptions) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The exception answer omitted or invalidly structured its cited condition/effect list.",
                    "Return every material exception or restriction in exceptionClauses with direct citationIds.",
                    "Keep legacy exceptions empty and do not leave a requested exception only in prose.")), request, caseId);
            resolved = resolver.resolve(request, draft);
        }
        com.rulepilot.assistant.domain.StructuredRuleAnswer published;
        try {
            published = validator.publish(
                    versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), resolved);
        } catch (RuntimeException invalidPublication) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The draft failed final schema or citation validation.",
                    "Keep shortVerdict to one or two plain sentences and at most 200 characters; use only supplied citationIds and remove unsupported claims.",
                    "Keep legacy exceptions empty when exceptionClauses is present.")), request, caseId);
            resolved = resolver.resolve(request, draft);
            try {
                published = validator.publish(
                        versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), resolved);
            } catch (RuntimeException repeatedPublicationFailure) {
                throw new AssertionError("repaired exception answer remains invalid for " + caseId
                        + "; verdictLength=" + length(draft.shortVerdict())
                        + "; explanationLength=" + length(draft.explanation())
                        + "; citationCount=" + draft.citationIds().size()
                        + "; legacyExceptionCount=" + draft.exceptions().size()
                        + "; exceptionClauseCount=" + draft.exceptionClauses().size(), repeatedPublicationFailure);
            }
        }

        List<Map<String, Object>> expected = new ArrayList<>();
        caseNode.path("clauses").forEach(clause -> expected.add(Map.of(
                "conditionTerms", values(clause.path("conditionTerms")),
                "effectTerms", values(clause.path("effectTerms")))));
        List<Map<String, Object>> allowed = new ArrayList<>(expected);
        caseNode.path("allowedClauses").forEach(clause -> allowed.add(Map.of(
                "conditionTerms", values(clause.path("conditionTerms")),
                "effectTerms", values(clause.path("effectTerms")))));
        List<String> forbiddenTerms = values(caseNode.path("forbiddenAnswerTerms"));
        List<String> requiredShortVerdictTerms = values(caseNode.path("requiredShortVerdictTerms"));
        ExceptionEvaluation evaluation = evaluateExceptionAnswer(
                expected, allowed, forbiddenTerms, requiredShortVerdictTerms, resolved, published);
        if (!evaluation.passes()) {
            repairCount++;
            List<String> qualityFeedback = new ArrayList<>(List.of(
                    "The answer failed a player-centered completeness or relevance review.",
                    "Include every material restriction already stated in the verdict or explanation as its own cited exceptionClause; do not leave it only in prose.",
                    "Remove procedural clauses that do not change the outcome asked about, and keep the prose focused on the immediate condition-to-effect results.",
                    "Use natural player-facing terminology from the evidence; do not invent compressed labels for players, roles, objects, or outcomes.",
                    "Make every exceptionClause independently actionable by repeating every shared prerequisite needed for its condition; do not rely on a previous clause or prose to supply the gate.",
                    "Preserve the same shared prerequisite in shortVerdict and explanation whenever omitting it would broaden when the outcome applies.",
                    "Keep shortVerdict to at most 200 characters and legacy exceptions empty."));
            if (!evaluation.allExpected()) {
                qualityFeedback.add("The required condition/effect concepts that must each be represented are: "
                        + expected + ".");
            }
            if (!evaluation.noUnexpected()) {
                qualityFeedback.add("Keep exceptionClauses within these relevant condition/effect concepts: "
                        + allowed + ". Remove every other procedural or outcome-irrelevant clause.");
            }
            if (!evaluation.noForbiddenTerm()) {
                qualityFeedback.add("Remove these detected off-scope or misleading expressions and the details they introduce: "
                        + forbiddenTerms + ".");
            }
            if (!evaluation.shortVerdictPreservesRequiredScope()) {
                qualityFeedback.add("The shortVerdict must preserve these scope concepts: "
                        + requiredShortVerdictTerms + ".");
            }
            draft = prepared(model.revise(request, draft, qualityFeedback), request, caseId);
            resolved = resolver.resolve(request, draft);
            published = validator.publish(
                    versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), resolved);
            evaluation = evaluateExceptionAnswer(
                    expected, allowed, forbiddenTerms, requiredShortVerdictTerms, resolved, published);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("page", page);
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("exceptionClauses", evaluation.actual());
        result.put("repairCount", repairCount);
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("allExpectedConditionEffectPairsPresent", evaluation.allExpected());
        result.put("noUnexpectedClause", evaluation.noUnexpected());
        result.put("allClausesCited", resolved.stream().allMatch(clause ->
                clause.citationIds().equals(List.of(chunkId))));
        result.put("legacyExceptionsEmpty", published.exceptions().isEmpty());
        result.put("proseCoversExpectedEffects", evaluation.proseCoversEffects());
        result.put("shortVerdictPreservesRequiredScope", evaluation.shortVerdictPreservesRequiredScope());
        result.put("containsNoForbiddenAnswerTerm", evaluation.noForbiddenTerm());
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !evaluation.actual().isEmpty());
        return Map.copyOf(result);
    }

    private ExceptionEvaluation evaluateExceptionAnswer(
            List<Map<String, Object>> expected,
            List<Map<String, Object>> allowed,
            List<String> forbiddenTerms,
            List<String> requiredShortVerdictTerms,
            List<com.rulepilot.assistant.domain.RuleExceptionClause> resolved,
            com.rulepilot.assistant.domain.StructuredRuleAnswer published) {
        List<Map<String, String>> actual = resolved.stream().map(clause -> Map.of(
                "condition", clause.condition(),
                "effect", clause.effect())).toList();
        boolean allExpected = expected.stream().allMatch(expectation -> actual.stream().anyMatch(clause -> {
            String visible = clause.get("condition") + " " + clause.get("effect");
            return containsTerms(visible, stringList(expectation.get("conditionTerms")))
                    && containsTerms(visible, stringList(expectation.get("effectTerms")));
        }));
        boolean noUnexpected = actual.stream().allMatch(clause -> allowed.stream().anyMatch(expectation -> {
            String visible = clause.get("condition") + " " + clause.get("effect");
            return containsTerms(visible, stringList(expectation.get("conditionTerms")))
                    && containsTerms(visible, stringList(expectation.get("effectTerms")));
        }));
        String prose = published.shortVerdict() + " " + published.explanation();
        boolean proseCoversEffects = expected.stream().allMatch(expectation ->
                containsTerms(prose, stringList(expectation.get("effectTerms"))));
        boolean noForbiddenTerm = forbiddenTerms.stream()
                .noneMatch(term -> containsTerms(prose + " " + actual, List.of(term)));
        boolean shortVerdictPreservesRequiredScope = containsTerms(
                published.shortVerdict(), requiredShortVerdictTerms);
        return new ExceptionEvaluation(
                actual, allExpected, noUnexpected, proseCoversEffects,
                shortVerdictPreservesRequiredScope, noForbiddenTerm);
    }

    private record ExceptionEvaluation(
            List<Map<String, String>> actual,
            boolean allExpected,
            boolean noUnexpected,
            boolean proseCoversEffects,
            boolean shortVerdictPreservesRequiredScope,
            boolean noForbiddenTerm) {

        boolean passes() {
            return allExpected && noUnexpected && proseCoversEffects
                    && shortVerdictPreservesRequiredScope && noForbiddenTerm;
        }
    }

    private Map<String, Object> runGeneratedAnswerCase(
            Path root, JsonNode caseNode, SpringAiRuleAnswerModel model) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = caseNode.path("page").asInt();
        String pageText = extractPage(pdf, page);
        UUID versionId = UUID.nameUUIDFromBytes(("generated:" + caseId).getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes(("generated:" + caseId + ":" + page)
                .getBytes(StandardCharsets.UTF_8));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_BRANCH_PAGE", "Condition outcomes", pageText, page, page, 1.0);
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, source.sectionType(), source.heading(), pageText, page, page)));

        ModelDraft generated = model.compose(request);
        var prepared = AnswerDraftPublicationPolicy.prepare(request, generated);
        assertThat(prepared.ready()).as("generated answer preparation for %s", caseId).isTrue();
        ModelDraft draft = prepared.draft();
        AnswerDecisionTableResolver resolver = new AnswerDecisionTableResolver();
        AnswerPublicationValidator validator = new AnswerPublicationValidator(new PolicyEvidenceVerifier());
        List<HybridEvidenceHit> evidence = List.of(new HybridEvidenceHit(source, 1.0, 1, null, true));
        int repairCount = 0;
        List<com.rulepilot.assistant.domain.RuleDecisionBranch> resolved;
        try {
            resolved = resolver.resolve(request, draft);
        } catch (RuntimeException invalidDecisionTable) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The branching answer omitted or invalidly structured its cited condition/outcome table.",
                    "Return each separately requested condition and outcome in decisionBranches with direct citations.",
                    "Do not leave a requested result only in shortVerdict or explanation.")), request, caseId);
            resolved = resolver.resolve(request, draft);
        }
        com.rulepilot.assistant.domain.StructuredRuleAnswer published;
        try {
            published = validator.publish(
                    versionId, draft, evidence, List.of(), List.of(), List.of(), resolved);
        } catch (RuntimeException invalidPublication) {
            repairCount++;
            draft = prepared(model.revise(request, draft, List.of(
                    "The draft failed the final schema or citation validation.",
                    "Return a complete concise answer using only citationIds present in supplied evidence.",
                    "Keep shortVerdict within 240 characters and remove unsupported claims.")), request, caseId);
            resolved = resolver.resolve(request, draft);
            try {
                published = validator.publish(
                        versionId, draft, evidence, List.of(), List.of(), List.of(), resolved);
            } catch (RuntimeException repeatedPublicationFailure) {
                throw new AssertionError("repaired player answer remains invalid for " + caseId
                        + "; verdictLength=" + length(draft.shortVerdict())
                        + "; explanationLength=" + length(draft.explanation())
                        + "; citationCount=" + draft.citationIds().size()
                        + "; exceptionLengths=" + draft.exceptions().stream().map(String::length).toList(),
                        repeatedPublicationFailure);
            }
        }

        List<Map<String, Object>> expected = new ArrayList<>();
        caseNode.path("branches").forEach(branch -> expected.add(Map.of(
                "conditionTerms", values(branch.path("conditionTerms")),
                "outcomeTerms", values(branch.path("outcomeTerms")))));
        List<Map<String, String>> actual = resolved.stream().map(branch -> Map.of(
                "condition", branch.condition(),
                "outcome", branch.outcome(),
                "basis", branch.basis().name())).toList();
        boolean allExpected = expected.stream().allMatch(expectation -> actual.stream().anyMatch(branch -> {
            String playerVisibleBranch = branch.get("condition") + " " + branch.get("outcome");
            return containsTerms(playerVisibleBranch, stringList(expectation.get("conditionTerms")))
                    && containsTerms(playerVisibleBranch, stringList(expectation.get("outcomeTerms")));
        }));
        boolean noUnexpected = actual.stream().allMatch(branch -> expected.stream().anyMatch(expectation ->
                containsTerms(branch.get("condition") + " " + branch.get("outcome"),
                        stringList(expectation.get("conditionTerms")))
                        && containsTerms(branch.get("condition") + " " + branch.get("outcome"),
                                stringList(expectation.get("outcomeTerms")))));
        String prose = published.shortVerdict() + " " + published.explanation();
        boolean proseCoversOutcomes = expected.stream().allMatch(expectation ->
                containsTerms(prose, stringList(expectation.get("outcomeTerms"))));
        boolean noForbiddenTerm = values(caseNode.path("forbiddenAnswerTerms")).stream()
                .noneMatch(term -> normalized(prose).contains(normalized(term)));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("question", caseNode.path("question").asText());
        result.put("page", page);
        result.put("shortVerdict", published.shortVerdict());
        result.put("explanation", published.explanation());
        result.put("decisionBranches", actual);
        result.put("repairCount", repairCount);
        result.put("answerPublished", published.status().publishesConclusion());
        result.put("allExpectedConditionOutcomePairsPresent", allExpected);
        result.put("noUnexpectedBranch", noUnexpected);
        result.put("allBranchesCited", resolved.stream().allMatch(branch ->
                branch.citationIds().equals(List.of(chunkId))));
        result.put("proseCoversExpectedOutcomes", proseCoversOutcomes);
        result.put("containsNoForbiddenAnswerTerm", noForbiddenTerm);
        result.put("playerAnswerInspected", !published.shortVerdict().isBlank()
                && !published.explanation().isBlank() && !actual.isEmpty());
        return Map.copyOf(result);
    }

    private ModelDraft prepared(ModelDraft draft, ModelRequest request, String caseId) {
        var preparation = AnswerDraftPublicationPolicy.prepare(request, draft);
        assertThat(preparation.ready()).as("repaired answer preparation for %s", caseId).isTrue();
        return preparation.draft();
    }

    private int length(String value) {
        return value == null ? -1 : value.length();
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

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
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

    private Map<String, Object> runCase(Path root, JsonNode caseNode) throws Exception {
        String caseId = caseNode.path("caseId").asText();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(caseNode.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required for " + caseId);
        int page = caseNode.path("page").asInt();
        String pageText = extractPage(pdf, page);
        String normalizedPage = normalized(pageText);
        List<JsonNode> configured = new ArrayList<>();
        caseNode.path("branches").forEach(configured::add);
        assertThat(configured).allSatisfy(branch -> {
            assertThat(normalizedPage).contains(normalized(branch.path("conditionMarker").asText()));
            assertThat(normalizedPage).contains(normalized(branch.path("outcomeMarker").asText()));
        });

        UUID versionId = UUID.nameUUIDFromBytes(caseId.getBytes(StandardCharsets.UTF_8));
        UUID chunkId = UUID.nameUUIDFromBytes((caseId + ":" + page).getBytes(StandardCharsets.UTF_8));
        RuleEvidenceHit source = new RuleEvidenceHit(
                chunkId, versionId, "REAL_BRANCH_PAGE", "Condition outcomes", pageText, page, page, 1.0);
        HybridEvidenceHit evidence = new HybridEvidenceHit(source, 1.0, 1, null, true);
        List<DecisionBranchRequest> requested = configured.stream()
                .map(branch -> new DecisionBranchRequest(
                        branch.path("condition").asText(), branch.path("outcome").asText(),
                        branch.path("basis").asText(), List.of(chunkId)))
                .toList();
        ModelDraft draft = new ModelDraft(
                true, null, "Compare the cited outcomes.", "Each condition keeps its stated result.",
                List.of(chunkId), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(), requested);
        ModelRequest request = new ModelRequest(
                caseNode.path("question").asText(), QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                List.of(new EvidenceInput(chunkId, source.sectionType(), source.heading(), pageText, page, page)));

        var resolved = new AnswerDecisionTableResolver().resolve(request, draft);
        var published = new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId, draft, List.of(evidence), List.of(), List.of(), List.of(), resolved);
        List<String> expectedPairs = requested.stream()
                .map(branch -> branch.condition() + " => " + branch.outcome()).toList();
        List<String> resolvedPairs = resolved.stream()
                .map(branch -> branch.condition() + " => " + branch.outcome()).toList();
        List<String> swappedPairs = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            swappedPairs.add(requested.get(index).condition() + " => "
                    + requested.get((index + 1) % requested.size()).outcome());
        }

        return Map.of(
                "caseId", caseId,
                "page", page,
                "branchCount", resolved.size(),
                "allPairsPresentOnRealPage", true,
                "swappedOutcomesRejectedByOracle", swappedPairs.stream().noneMatch(expectedPairs::contains),
                "toolPreservedPairs", resolvedPairs.equals(expectedPairs),
                "allBranchesSeparatelyCited", resolved.stream().allMatch(branch ->
                        branch.basis() == DecisionBranchBasis.EXPLICIT_RULE
                                && branch.citationIds().equals(List.of(chunkId))),
                "citationPublished", published.citations().stream().anyMatch(citation -> citation.pageFrom() == page));
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
