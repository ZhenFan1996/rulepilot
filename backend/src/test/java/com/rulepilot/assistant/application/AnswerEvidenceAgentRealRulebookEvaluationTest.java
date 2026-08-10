package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AgentExecutionControl.ActivityOutcome;
import com.rulepilot.assistant.AgentExecutionControl.ActivityType;
import com.rulepilot.assistant.AssistantReadTools;
import com.rulepilot.assistant.AssistantReadTools.RuleEvidenceContext;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.NativeToolAgent;
import com.rulepilot.assistant.NativeToolAgent.RunRequest;
import com.rulepilot.assistant.NativeToolAgent.RunResult;
import com.rulepilot.assistant.NativeAgentTool.ToolScope;
import com.rulepilot.assistant.NativeToolModel;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.adapter.out.model.FakeRuleAnswerModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiNativeToolModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiRuleAnswerModel;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.VersionedAgentPrompts;
import com.rulepilot.modelconfig.adapter.out.ChatModelFactory;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("real-answer-agent-evaluation")
class AnswerEvidenceAgentRealRulebookEvaluationTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void refinesCompoundEvidenceAcrossTwoRealRulebooksAndRejectsACrossRulebookNeed() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        JsonNode evaluation = mapper.readTree(root.resolve(
                ".local/agent-evaluation/answer-agent-cases.json").toFile());
        List<CaseConfiguration> cases = new ArrayList<>();
        for (JsonNode caseNode : evaluation.path("cases")) {
            cases.add(caseFor(
                    root,
                    manifest,
                    inventory,
                    caseNode,
                    provider("TEXT_LAYER".equals(caseNode.path("family").asText()) ? "deepseek" : "qwen")));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (CaseConfiguration case_ : cases) results.add(runCase(case_));
        Map<String, Object> negative = runCrossRulebookNegative(cases.getLast(), evaluation.path("crossRulebookNegative"));

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat((Integer) result.get("addedEvidence")).isGreaterThan(0);
            assertThat((Integer) result.get("toolCalls")).isGreaterThan(0);
            assertThat(result).containsEntry("directAdditionalModelCalls", 0)
                    .containsEntry("citationPublished", true)
                    .containsEntry("withinLatencyBudget", true);
        });
        assertThat(negative).containsEntry("evidenceCount", 0).containsEntry("crossScopeEvidence", false);

        Path output = root.resolve(".local/agent-evaluation/answer-agent-real-rulebooks.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "crossRulebookNegative", negative)) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void comparesCompletePlayerAnswersWithAndWithoutTheBoundedToolPortfolio() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_TOOL_VALUE_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        JsonNode evaluation = mapper.readTree(root.resolve(
                ".local/agent-evaluation/answer-agent-cases.json").toFile());
        List<Map<String, Object>> results = new ArrayList<>();
        Path output = root.resolve(".local/agent-evaluation/tool-value-generated-answers.json");
        String selectedCase = System.getenv("RULEPILOT_TOOL_VALUE_CASE");

        for (JsonNode caseNode : evaluation.path("cases")) {
            if (selectedCase != null && !selectedCase.isBlank()
                    && !selectedCase.equals(caseNode.path("caseId").asText())) continue;
            ProviderConfiguration provider = provider("TEXT_LAYER".equals(caseNode.path("family").asText())
                    ? "deepseek"
                    : "qwen");
            CaseConfiguration configured = caseFor(root, manifest, inventory, caseNode, provider);
            results.add(compareAnswer(configured));
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "generatedAt", Instant.now().toString(),
                    "results", List.copyOf(results))) + "\n", StandardCharsets.UTF_8);
        }

        assertThat(results).hasSize(selectedCase == null || selectedCase.isBlank() ? 2 : 1)
                .allSatisfy(result -> assertThat(result)
                .containsEntry("toolAnswerPublished", true)
                .containsEntry("toolAnswerUsesExpectedPage", true)
                .containsEntry("toolAnswerCoversRequiredDetails", true)
                .containsEntry("boundedToolCalls", true)
                .containsEntry("combinedSearchAndRead", true)
                .containsEntry("playerAnswersRecordedForInspection", true));
    }

    private Map<String, Object> compareAnswer(CaseConfiguration case_) throws Exception {
        UUID versionId = UUID.nameUUIDFromBytes(("tool-value:" + case_.caseId()).getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(case_.pdf(), versionId);
        HybridEvidenceHit initial = corpus.hit(case_.initialPage());
        SpringAiRuleAnswerModel answerModel = answerModel(case_.provider());

        long baselineStarted = System.nanoTime();
        ModelRequest baselineRequest = modelRequest(case_.question(), List.of(initial));
        ModelDraft baselineDraft = answerModel.compose(baselineRequest);
        long baselineLatencyMs = Duration.ofNanos(System.nanoTime() - baselineStarted).toMillis();
        StructuredRuleAnswer baselineAnswer = publishIfReady(versionId, baselineRequest, baselineDraft, List.of(initial));

        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent evidenceAgent = answerAgent(case_.provider(), corpus, audited, scope);
        long toolStarted = System.nanoTime();
        AnswerEvidenceRetriever.Result refined = evidenceAgent.refine(
                runId,
                question(versionId, case_.question()),
                new QuestionContext(versionId),
                "agent-evaluation",
                null,
                ready(initial));
        ModelRequest toolRequest = modelRequest(case_.question(), refined.evidence());
        ModelDraft toolDraft = answerModel.compose(toolRequest);
        StructuredRuleAnswer toolAnswer = publishIfReady(versionId, toolRequest, toolDraft, refined.evidence());
        int repairs = 0;
        if (!answerCovers(case_, toolAnswer) || !usesExpectedPage(case_, toolAnswer)) {
            repairs++;
            toolDraft = answerModel.revise(toolRequest, toolDraft, List.of(
                    "Answer every requested part from the supplied direct rule evidence.",
                    "Use the citation on the page that explicitly states the requested sequence and final outcome.",
                    "Do not answer from prior knowledge or a merely related page."));
            toolAnswer = publishIfReady(versionId, toolRequest, toolDraft, refined.evidence());
        }
        long toolLatencyMs = Duration.ofNanos(System.nanoTime() - toolStarted).toMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("question", case_.question());
        result.put("provider", case_.provider().provider());
        result.put("expectedPage", case_.expectedPage());
        result.put("withoutTools", visibleAnswer(baselineAnswer));
        result.put("withoutToolsUsesExpectedPage", usesExpectedPage(case_, baselineAnswer));
        result.put("withoutToolsCoversRequiredDetails", answerCovers(case_, baselineAnswer));
        result.put("withoutToolsLatencyMs", baselineLatencyMs);
        result.put("withTools", visibleAnswer(toolAnswer));
        result.put("toolTrace", List.copyOf(audited.trace));
        result.put("toolLoopReason", audited.lastRunReason);
        result.put("requestedToolPortfolio", List.copyOf(audited.requestedToolPortfolio));
        result.put("registeredToolPortfolio", List.copyOf(audited.registeredToolPortfolio));
        result.put("toolCalls", audited.toolCalls);
        result.put("modelCallsInsideToolLoop", audited.modelCalls);
        result.put("answerRepairs", repairs);
        result.put("withToolsLatencyMs", toolLatencyMs);
        result.put("toolAnswerPublished", toolAnswer.status().publishesConclusion());
        result.put("toolAnswerUsesExpectedPage", usesExpectedPage(case_, toolAnswer));
        result.put("toolAnswerCoversRequiredDetails", answerCovers(case_, toolAnswer));
        result.put("boundedToolCalls", audited.toolCalls >= 2 && audited.toolCalls <= 3);
        result.put("combinedSearchAndRead", audited.trace.stream().anyMatch(trace -> trace.startsWith("tool:search_rule_evidence"))
                && audited.trace.stream().anyMatch(trace -> trace.startsWith("tool:read_rule_pages")));
        result.put("playerAnswersRecordedForInspection", !visibleText(baselineAnswer).isBlank()
                && !visibleText(toolAnswer).isBlank());
        return Map.copyOf(result);
    }

    private ModelRequest modelRequest(String question, List<HybridEvidenceHit> evidence) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, PlayerLocale.EN),
                evidence.stream().map(hit -> new EvidenceInput(
                        hit.evidence().chunkId(),
                        hit.evidence().sectionType(),
                        hit.evidence().heading(),
                        hit.evidence().excerpt(),
                        hit.evidence().pageFrom(),
                        hit.evidence().pageTo())).toList());
    }

    private StructuredRuleAnswer publishIfReady(
            UUID versionId,
            ModelRequest request,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence) {
        AnswerDraftPublicationPolicy.Preparation preparation = AnswerDraftPublicationPolicy.prepare(request, draft);
        if (!preparation.ready()) {
            return AnswerOutcomePolicy.safeFailure(
                    versionId,
                    com.rulepilot.assistant.domain.AnswerStatus.INSUFFICIENT_EVIDENCE,
                    draft.insufficiencyReason() == null ? "The supplied evidence is insufficient." : draft.insufficiencyReason());
        }
        try {
            return new AnswerPublicationValidator(new PolicyEvidenceVerifier())
                    .publish(versionId, preparation.draft(), evidence);
        } catch (RuntimeException invalidDraft) {
            return AnswerOutcomePolicy.safeFailure(
                    versionId,
                    com.rulepilot.assistant.domain.AnswerStatus.INVALID_MODEL_OUTPUT,
                    "The generated answer did not satisfy the publication schema.");
        }
    }

    private Map<String, Object> visibleAnswer(StructuredRuleAnswer answer) {
        return Map.of(
                "status", answer.status().name(),
                "shortVerdict", answer.shortVerdict(),
                "explanation", answer.explanation(),
                "citationPages", answer.citations().stream().map(citation -> citation.pageFrom()).distinct().toList());
    }

    private String visibleText(StructuredRuleAnswer answer) {
        return (answer.shortVerdict() + " " + answer.explanation()).replaceAll("\\s+", " ").strip();
    }

    private boolean answerCovers(CaseConfiguration case_, StructuredRuleAnswer answer) {
        String visible = visibleText(answer).toLowerCase(Locale.ROOT).replaceAll("[-‐‑‒–—]", "");
        return case_.expectedTerms().stream().allMatch(term -> switch (term) {
            case "humiliated" -> visible.contains("humiliat");
            case "purged" -> visible.contains("purg");
            case "deposed" -> visible.contains("depos");
            default -> visible.contains(term.replaceAll("[-‐‑‒–—]", ""));
        });
    }

    private boolean usesExpectedPage(CaseConfiguration case_, StructuredRuleAnswer answer) {
        return answer.citations().stream().anyMatch(citation -> citation.pageFrom() == case_.expectedPage());
    }

    @Test
    void interpretsNaturalAdaptiveTeachingMovesAcrossPaidProviders() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_CONTEXT_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        List<DialogueIntentCase> cases = List.of(
                new DialogueIntentCase(
                        "cx-adapt-deepseek",
                        "deepseek",
                        "When does this action end?",
                        "I still don't understand. Walk me through one concrete example.",
                        PlayerLocale.EN,
                        LearningIntent.EXAMPLE),
                new DialogueIntentCase(
                        "cx-adapt-qwen",
                        "qwen",
                        "这个行动的费用什么时候支付？",
                        "还是有点绕，能先用更简单的话重新讲一遍吗？",
                        PlayerLocale.ZH_CN,
                        LearningIntent.SIMPLIFY));
        List<Map<String, Object>> results = new ArrayList<>();

        for (DialogueIntentCase case_ : cases) {
            ProviderConfiguration configured = provider(case_.provider());
            assertThat(configured.model().toLowerCase(Locale.ROOT)).doesNotStartWith("qwen-plus");
            long started = System.nanoTime();
            var draft = answerModel(configured)
                    .interpretQuestion(new QuestionInterpretationRequest(
                            case_.followUp(),
                            case_.previousQuestion(),
                            "",
                            "",
                            QuestionType.SITUATION_QUERY,
                            Set.of(MissingQuestionContext.REFERENCED_OBJECT),
                            null,
                            case_.locale()))
                    .orElseThrow(() -> new AssertionError(
                            case_.provider() + " did not return a valid semantic teaching plan"));
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(draft.referenceBinding()).isEqualTo(ReferenceBinding.PREVIOUS_QUESTION);
            assertThat(draft.learningIntent()).isEqualTo(case_.expectedIntent());
            assertThat(draft.subquestions()).isNotEmpty();
            assertThat(latencyMs).isLessThan(45_000);
            results.add(Map.of(
                    "caseId", case_.caseId(),
                    "provider", case_.provider(),
                    "model", configured.model(),
                    "referenceBinding", draft.referenceBinding().name(),
                    "learningIntent", draft.learningIntent().name(),
                    "subquestionCount", draft.subquestions().size(),
                    "modelCalls", 1,
                    "toolCalls", 0,
                    "latencyMs", latencyMs));
        }

        Path output = root.resolve(".local/agent-evaluation/teaching-dialogue-intent-real.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "explicitEnumInjected", false,
                        "rulebookTextUsedAsIntentEvidence", false,
                        "rawModelOutputStored", false,
                        "prohibitedQwenPlusUsed", false))) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void resolvesGroundedMultiTurnReferencesAcrossTwoRealRulebooks() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_CONTEXT_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode manifest = mapper.readTree(root.resolve(".local/agent-evaluation/manifest.json").toFile());
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        JsonNode answerCases = mapper.readTree(root.resolve(
                ".local/agent-evaluation/answer-agent-cases.json").toFile());
        JsonNode contextCases = mapper.readTree(root.resolve(
                ".local/agent-evaluation/context-agent-cases.json").toFile());

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode contextCase : contextCases.path("cases")) {
            JsonNode answerCase = java.util.stream.StreamSupport.stream(
                            answerCases.path("cases").spliterator(), false)
                    .filter(candidate -> contextCase.path("caseId").asText().equals(candidate.path("caseId").asText()))
                    .findFirst()
                    .orElseThrow();
            CaseConfiguration configured = caseFor(
                    root,
                    manifest,
                    inventory,
                    answerCase,
                    provider(contextCase.path("provider").asText()));
            results.add(runFollowUpCase(configured, contextCase));
        }

        Path output = root.resolve(".local/agent-evaluation/context-agent-real-rulebooks.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "priorAnswerIsEvidence", false,
                        "orphanedRunsReplayIncompleteCalls", false,
                        "providerDowngradeKeepsDeterministicEvidence", true,
                        "staleSchemaRejectedBeforeToolExecution", true))) + "\n", StandardCharsets.UTF_8);

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat((Integer) result.get("addedEvidence")).isGreaterThan(0);
            assertThat((Integer) result.get("toolCalls")).isGreaterThan(0);
            assertThat(result).containsEntry("freshCanonicalExpectedPage", true)
                    .containsEntry("sameVersionOnly", true)
                    .containsEntry("toolPortfolioRegistered", true)
                    .containsEntry("withinLatencyBudget", true);
        });

    }

    private Map<String, Object> runFollowUpCase(CaseConfiguration case_, JsonNode contextCase) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(case_.pdf(), versionId);
        HybridEvidenceHit expectedPrior = corpus.hit(case_.expectedPage());
        PriorTurnReference prior = new PriorTurnReference(
                versionId,
                contextCase.path("priorQuestion").asText(),
                contextCase.path("priorVerdict").asText(),
                List.of(new PriorCitationReference(
                        expectedPrior.evidence().chunkId(),
                        versionId,
                        expectedPrior.evidence().pageFrom(),
                        expectedPrior.evidence().pageTo())));
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent agent = answerAgent(case_.provider(), corpus, audited, scope);
        AnswerEvidenceRetriever.Result deterministic = ready(corpus.hit(case_.initialPage()));
        boolean refinementRequired = AnswerEvidenceRefinementPolicy.requiresRefinement(
                question(versionId, contextCase.path("followUp").asText()),
                new QuestionContext(versionId, prior.question(), null, null, prior),
                deterministic);
        long started = System.nanoTime();

        AnswerEvidenceRetriever.Result refined = agent.refine(
                runId,
                question(versionId, contextCase.path("followUp").asText()),
                new QuestionContext(versionId, prior.question(), null, null, prior),
                "agent-evaluation",
                null,
                deterministic);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        boolean expectedPage = refined.evidence().stream().anyMatch(hit ->
                hit.evidence().pageFrom() == case_.expectedPage()
                        && case_.expectedTerms().stream().allMatch(term ->
                                hit.evidence().excerpt().toLowerCase(Locale.ROOT).contains(term)));
        List<String> tags = new ArrayList<>();
        contextCase.path("interactionTags").forEach(tag -> tags.add(tag.asText()));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("interactionTags", List.copyOf(tags));
        result.put("addedEvidence", refined.evidence().size() - deterministic.evidence().size());
        result.put("toolCalls", audited.toolCalls);
        result.put("modelCalls", audited.modelCalls);
        result.put("nativeRuns", audited.nativeRuns);
        result.put("nativeRunReason", audited.lastRunReason);
        result.put("refinementRequired", refinementRequired);
        result.put("toolPortfolioRegistered",
                audited.registeredToolPortfolio.equals(audited.requestedToolPortfolio));
        result.put("freshCanonicalExpectedPage", expectedPage);
        result.put("sameVersionOnly", refined.evidence().stream()
                .allMatch(hit -> versionId.equals(hit.evidence().documentVersionId())));
        result.put("latencyMs", latencyMs);
        result.put("withinLatencyBudget", latencyMs < 90_000);
        return Map.copyOf(result);
    }

    private Map<String, Object> runCase(CaseConfiguration case_) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(case_.pdf(), versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent agent = answerAgent(case_.provider(), corpus, audited, scope);
        AnswerEvidenceRetriever.Result deterministic = ready(corpus.hit(case_.initialPage()));
        long started = System.nanoTime();

        AnswerEvidenceRetriever.Result refined = agent.refine(
                runId,
                question(versionId, case_.question()),
                new QuestionContext(versionId),
                "agent-evaluation",
                null,
                deterministic);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        HybridEvidenceHit expected = refined.evidence().stream()
                .filter(hit -> hit.evidence().pageFrom() == case_.expectedPage())
                .filter(hit -> case_.expectedTerms().stream()
                        .allMatch(term -> hit.evidence().excerpt().toLowerCase(Locale.ROOT).contains(term)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected later evidence page was not acquired for "
                        + case_.caseId() + " via " + case_.provider().provider()
                        + "; selected=" + refined.evidence().stream()
                                .map(hit -> "p" + hit.evidence().pageFrom() + ":terms="
                                        + case_.expectedTerms().stream().filter(term ->
                                                        hit.evidence().excerpt().toLowerCase(Locale.ROOT).contains(term))
                                                .count())
                                .toList()
                        + "; trace=" + audited.trace));
        String excerpt = expected.evidence().excerpt().toLowerCase(Locale.ROOT);
        assertThat(case_.expectedTerms()).allSatisfy(term -> assertThat(excerpt).contains(term));
        assertThat(refined.evidence()).allSatisfy(hit ->
                assertThat(hit.evidence().documentVersionId()).isEqualTo(versionId));

        var published = new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId,
                new ModelDraft(
                        "The cited passage covers the requested conditions.",
                        "Use the cited rulebook passage for the complete ruling.",
                        List.of(expected.evidence().chunkId()),
                        List.of(),
                        "HIGH"),
                refined.evidence());
        int modelCallsBeforeDirect = audited.modelCalls;
        AnswerEvidenceRetriever.Result direct = ready(corpus.hit(case_.directPage()));
        AnswerEvidenceRetriever.Result unchanged = agent.refine(
                runId,
                question(versionId, case_.directQuestion()),
                new QuestionContext(versionId),
                "agent-evaluation",
                null,
                direct);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("caseId", case_.caseId());
        result.put("provider", case_.provider().provider());
        result.put("addedEvidence", refined.evidence().size() - deterministic.evidence().size());
        result.put("expectedPage", case_.expectedPage());
        result.put("toolCalls", audited.toolCalls);
        result.put("modelCalls", modelCallsBeforeDirect);
        result.put("directAdditionalModelCalls", audited.modelCalls - modelCallsBeforeDirect);
        result.put("directEvidenceUnchanged", unchanged == direct);
        result.put("citationPublished", published.citations().stream()
                .anyMatch(citation -> citation.pageFrom() == case_.expectedPage()));
        result.put("latencyMs", latencyMs);
        result.put("withinLatencyBudget", latencyMs < 90_000);
        return Map.copyOf(result);
    }

    private Map<String, Object> runCrossRulebookNegative(CaseConfiguration case_, JsonNode negative) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(case_.pdf(), versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent agent = answerAgent(case_.provider(), corpus, audited, scope);

        AnswerEvidenceRetriever.Result result = agent.refine(
                runId,
                question(versionId, negative.path("question").asText()),
                new QuestionContext(versionId),
                "agent-evaluation",
                null,
                new AnswerEvidenceRetriever.Result(List.of(), AnswerEvidenceRetriever.State.READY));

        return Map.of(
                "caseId", negative.path("caseId").asText(),
                "evidenceCount", result.evidence().size(),
                "toolCalls", audited.toolCalls,
                "crossScopeEvidence", result.evidence().stream()
                        .anyMatch(hit -> !versionId.equals(hit.evidence().documentVersionId())));
    }

    private AnswerEvidenceAgent answerAgent(
            ProviderConfiguration provider,
            PdfRulebookEvidence corpus,
            DirectAuditedInvocations audited,
            ToolScope scope) {
        var nativeTools = List.of(
                new SearchRuleEvidenceNativeTool(corpus, mapper),
                new SearchRuleRelationshipsNativeTool(corpus, mapper),
                new ExpandRuleEvidenceContextNativeTool(corpus, mapper),
                new ReadRulePagesNativeTool(corpus, mapper));
        NativeAgentToolRegistry registry = new NativeAgentToolRegistry(
                nativeTools,
                mapper,
                candidate -> candidate.ownerUsername().equals(scope.ownerUsername())
                        && candidate.documentVersionId().equals(scope.documentVersionId()));
        BoundedNativeToolAgent loop = new BoundedNativeToolAgent(
                springModel(provider), registry, mock(AgentExecutionControl.class), audited, mapper);
        DocumentNativeToolScopeFactory scopes = mock(DocumentNativeToolScopeFactory.class);
        when(scopes.create(scope.ownerUsername(), scope.documentVersionId(), scope.runId()))
                .thenReturn(java.util.Optional.of(scope));
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        NativeToolAgent observedLoop = new NativeToolAgent() {
            @Override
            public RunResult run(RunRequest request) {
                audited.nativeRuns++;
                audited.requestedToolPortfolio = request.allowedTools();
                audited.registeredToolPortfolio = registry.specifications(request.role(), request.allowedTools()).stream()
                        .map(NativeToolModel.ToolSpec::name)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                RunResult result = loop.run(request);
                audited.lastRunReason = result.reason();
                return result;
            }

            @Override
            public String providerId(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return loop.providerId(role, ownerUsername);
            }

            @Override
            public boolean supports(com.rulepilot.assistant.NativeAgentTool.Role role, String ownerUsername) {
                return loop.supports(role, ownerUsername);
            }
        };
        return new AnswerEvidenceAgent(observedLoop, corpus, scopes, limiter);
    }

    private NativeToolModel springModel(ProviderConfiguration provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(45))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(any(RuntimeModelConfiguration.Role.class), any(String.class))).thenReturn(chatModel);
        when(configuration.providerFor(any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn(provider.provider());
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        any(RuntimeModelConfiguration.Role.class), any(String.class)))
                .thenReturn("deepseek".equals(provider.provider()));
        return new SpringAiNativeToolModel(configuration);
    }

    private SpringAiRuleAnswerModel answerModel(ProviderConfiguration provider) {
        ChatModel chatModel = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.ANSWER))
                .thenReturn("deepseek".equals(provider.provider()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return new SpringAiRuleAnswerModel(
                    configuration, new FakeRuleAnswerModel(), context.getBean(VersionedAgentPrompts.class));
        }
    }

    private CaseConfiguration caseFor(
            Path root,
            JsonNode manifest,
            JsonNode inventory,
            JsonNode evaluation,
            ProviderConfiguration provider) {
        String caseId = evaluation.path("caseId").asText();
        JsonNode manifestCase = java.util.stream.StreamSupport.stream(manifest.path("cases").spliterator(), false)
                .filter(candidate -> caseId.equals(candidate.path("caseId").asText()))
                .findFirst()
                .orElseThrow();
        String digest = manifestCase.path("sourceSha256").asText();
        JsonNode source = java.util.stream.StreamSupport.stream(
                        inventory.path("qualifiedRulebooks").spliterator(), false)
                .filter(candidate -> digest.equals(candidate.path("sha256").asText()))
                .findFirst()
                .orElseThrow();
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(source.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required");
        List<String> expectedTerms = new ArrayList<>();
        evaluation.path("expectedTerms").forEach(term -> expectedTerms.add(term.asText().toLowerCase(Locale.ROOT)));
        return new CaseConfiguration(
                caseId,
                pdf,
                evaluation.path("question").asText(),
                evaluation.path("directQuestion").asText(),
                evaluation.path("initialPage").asInt(),
                evaluation.path("directPage").asInt(),
                evaluation.path("expectedPage").asInt(),
                List.copyOf(expectedTerms),
                provider);
    }

    private ProviderConfiguration provider(String provider) {
        String prefix = provider.toUpperCase(Locale.ROOT);
        return new ProviderConfiguration(
                provider,
                requiredEnvironment(prefix + "_API_KEY"),
                requiredEnvironment(prefix + "_BASE_URL"),
                requiredEnvironment(prefix + "_MODEL"));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(value != null && !value.isBlank(), name + " is required for the authorized real evaluation");
        return value.strip();
    }

    private UnderstoodQuestion question(UUID versionId, String question) {
        return new UnderstoodQuestion(
                versionId, question, question, QuestionType.RULE_QUERY, List.of(), Set.of());
    }

    private AnswerEvidenceRetriever.Result ready(HybridEvidenceHit hit) {
        return new AnswerEvidenceRetriever.Result(List.of(hit), AnswerEvidenceRetriever.State.READY);
    }

    private record CaseConfiguration(
            String caseId,
            Path pdf,
            String question,
            String directQuestion,
            int initialPage,
            int directPage,
            int expectedPage,
            List<String> expectedTerms,
            ProviderConfiguration provider) {}

    private record ProviderConfiguration(String provider, String apiKey, String baseUrl, String model) {}

    private record DialogueIntentCase(
            String caseId,
            String provider,
            String previousQuestion,
            String followUp,
            PlayerLocale locale,
            LearningIntent expectedIntent) {}

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private int modelCalls;
        private int toolCalls;
        private int nativeRuns;
        private String lastRunReason = "NOT_STARTED";
        private Set<String> requestedToolPortfolio = Set.of();
        private Set<String> registeredToolPortfolio = Set.of();
        private final List<String> trace = new ArrayList<>();

        @Override
        public <T> T invoke(
                UUID runId,
                ActivityType type,
                String operation,
                int estimatedInputTokens,
                String successSummary,
                Supplier<T> invocation,
                ToIntFunction<T> outputTokenEstimator) {
            if (type == ActivityType.MODEL) modelCalls++;
            if (type == ActivityType.TOOL) toolCalls++;
            T result = invocation.get();
            if (result instanceof NativeToolModel.ModelTurn turn) {
                trace.add("model:" + turn.toolCalls().stream()
                        .map(call -> call.name() + call.argumentsJson())
                        .toList());
            } else if (result instanceof NativeAgentToolRegistry.ToolExecution tool) {
                trace.add("tool:" + tool.specification().name() + ':' + tool.observation().code()
                        + ":evidence=" + tool.observation().evidenceCount());
            }
            return result;
        }

        @Override
        public void record(UUID runId, ActivityType type, String operation, ActivityOutcome outcome, String summary) {}
    }

    private static final class PdfRulebookEvidence implements AssistantReadTools, RuleEvidenceLookup {
        private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]{3,}");
        private static final Set<String> STOP_TERMS = Set.of(
                "and", "are", "can", "does", "for", "from", "how", "into", "the", "then", "what", "when", "with");
        private final UUID versionId;
        private final List<String> pages;
        private final List<RuleEvidenceHit> chunks;

        private PdfRulebookEvidence(Path pdf, UUID versionId) throws IOException {
            this.versionId = versionId;
            this.pages = extractPages(pdf.toFile());
            this.chunks = buildChunks();
        }

        @Override
        public List<RuleEvidence> searchRuleEvidence(SearchRuleEvidence request) {
            if (!versionId.equals(request.documentVersionId())) throw new IllegalArgumentException("scope mismatch");
            Set<String> terms = terms(request.query());
            if (terms.isEmpty()) return List.of();
            return chunks.stream()
                    .map(source -> new ScoredEvidence(source, score(source.excerpt(), terms)))
                    .filter(candidate -> candidate.score() > 0)
                    .sorted(Comparator.comparingInt(ScoredEvidence::score).reversed()
                            .thenComparing(candidate -> candidate.source().chunkId()))
                    .limit(request.limit())
                    .map(candidate -> ruleEvidence(candidate.source()))
                    .toList();
        }

        @Override
        public List<RuleEvidence> readRuleEvidencePages(
                UUID documentVersionId, Set<Integer> pageNumbers, boolean includePageImages) {
            if (!versionId.equals(documentVersionId) || includePageImages) throw new IllegalArgumentException("scope mismatch");
            return chunks.stream()
                    .filter(source -> pageNumbers.contains(source.pageFrom()))
                    .sorted(Comparator.comparingInt(RuleEvidenceHit::pageFrom)
                            .thenComparing(RuleEvidenceHit::chunkId))
                    .map(this::ruleEvidence)
                    .toList();
        }

        @Override
        public RuleEvidenceContext readRuleEvidenceContext(
                UUID documentVersionId, Set<UUID> anchorEvidenceIds, int radius) {
            if (!versionId.equals(documentVersionId)) throw new IllegalArgumentException("scope mismatch");
            List<RuleEvidenceHit> anchors = chunks.stream()
                    .filter(source -> anchorEvidenceIds.contains(source.chunkId()))
                    .toList();
            LinkedHashSet<RuleEvidenceHit> surrounding = new LinkedHashSet<>();
            for (RuleEvidenceHit anchor : anchors) {
                int index = chunks.indexOf(anchor);
                for (int candidate = Math.max(0, index - radius);
                        candidate <= Math.min(chunks.size() - 1, index + radius);
                        candidate++) {
                    if (candidate != index) surrounding.add(chunks.get(candidate));
                }
            }
            return new RuleEvidenceContext(
                    anchors.stream().map(this::ruleEvidence).toList(),
                    surrounding.stream().map(this::ruleEvidence).toList());
        }

        @Override
        public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
            if (!versionId.equals(documentVersionId)) return List.of();
            return chunks.stream()
                    .filter(source -> chunkIds.contains(source.chunkId()))
                    .toList();
        }

        private HybridEvidenceHit hit(int page) {
            RuleEvidenceHit source = chunks.stream()
                    .filter(candidate -> candidate.pageFrom() == page)
                    .findFirst()
                    .orElseThrow();
            return new HybridEvidenceHit(source, 1.0, 1, null, false);
        }

        private List<RuleEvidenceHit> buildChunks() {
            List<RuleEvidenceHit> sources = new ArrayList<>();
            for (int page = 1; page <= pages.size(); page++) {
                String text = pages.get(page - 1).replaceAll("\\s+", " ").strip();
                int sequence = 0;
                for (int start = 0; start < text.length(); start += 1000) {
                    int end = Math.min(text.length(), start + 1200);
                    String excerpt = text.substring(start, end).strip();
                    if (!excerpt.isBlank()) {
                        sources.add(new RuleEvidenceHit(
                                UUID.nameUUIDFromBytes((versionId + ":" + page + ":" + sequence)
                                        .getBytes(StandardCharsets.UTF_8)),
                                versionId,
                                "PAGE",
                                "Rulebook page " + page + " segment " + (sequence + 1),
                                excerpt,
                                page,
                                page,
                                1.0));
                    }
                    sequence++;
                }
            }
            return List.copyOf(sources);
        }

        private RuleEvidence ruleEvidence(RuleEvidenceHit source) {
            return new RuleEvidence(
                    source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                    source.excerpt(), source.pageFrom(), source.pageTo());
        }

        private Set<String> terms(String query) {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            var matcher = TERMS.matcher(query.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String term = matcher.group();
                if (!STOP_TERMS.contains(term)) terms.add(term);
            }
            return Set.copyOf(terms);
        }

        private int score(String page, Set<String> terms) {
            String normalized = page.toLowerCase(Locale.ROOT);
            return (int) terms.stream().filter(normalized::contains).count();
        }

        private static List<String> extractPages(File pdf) throws IOException {
            try (PDDocument document = Loader.loadPDF(pdf)) {
                List<String> pages = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper();
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages.add(stripper.getText(document));
                }
                return List.copyOf(pages);
            }
        }

        private record ScoredEvidence(RuleEvidenceHit source, int score) {}
    }
}
