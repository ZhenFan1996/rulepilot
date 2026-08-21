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
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
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
import com.rulepilot.assistant.RuleAnswerModel.PlayerFacingField;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.adapter.out.model.FakeRuleAnswerModel;
import com.rulepilot.assistant.adapter.out.model.FakeContentCriticModel;
import com.rulepilot.assistant.adapter.out.model.SpringAiContentCriticModel;
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
import com.rulepilot.retrieval.AnswerEvidenceRetriever;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.teaching.VisualRulebookPageFacts.PageFact;
import io.micrometer.observation.ObservationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    void recordsOnePaidMixedCoverageAnswerCanaryWithoutOverwritingPriorEvidence() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_LEAN_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-lean-canary-" + phase + "-raw.jsonl");
        Path summaryOutput = root.resolve(".local/agent-evaluation/answer-lean-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "canary evidence already exists; choose a new phase label instead of overwriting it");

        String question = "这款游戏怎么赢？规则书有没有保证获胜的最佳开局？";
        long started = System.nanoTime();
        BoundaryAnswer run = runBoundaryAnswer(
                rulebook,
                configured,
                question,
                2,
                rawOutput,
                "mixed-supported-and-unsupported");
        long wallLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Map<String, Object> summary = new LinkedHashMap<>(boundarySummary(run));
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("publishedAnswer", visibleAnswer(run.answer()));
        summary.put("wallLatencyMs", wallLatencyMs);
        summary.put("sameVersionOnly", run.refined().evidence().stream()
                .map(hit -> hit.evidence().documentVersionId())
                .distinct()
                .count() == 1);
        String visible = visibleText(run.answer());
        boolean localUncertainty = visible.contains("当前")
                && (visible.contains("不能确认")
                        || visible.contains("无法确认")
                        || visible.contains("还不能确认")
                        || visible.contains("不能从现有")
                        || visible.contains("无法从现有")
                        || visible.contains("不能从当前")
                        || visible.contains("无法从当前")
                        || visible.contains("不能从这些摘录")
                        || visible.contains("无法从这些摘录"));
        boolean usefulClarification = visible.contains("？")
                || visible.contains("?")
                || visible.contains("如果你能指出")
                || visible.contains("如果你能告诉我")
                || visible.contains("请告诉我具体")
                || visible.contains("请提供具体");
        boolean wholeRulebookNegative = visible.contains("规则书本身没有")
                || visible.contains("规则书未提供")
                || visible.contains("规则书没有提供")
                || visible.contains("规则书只描述")
                || visible.contains("规则书只说明")
                || visible.contains("规则书未提到");
        summary.put("localUncertainty", localUncertainty);
        summary.put("usefulClarification", usefulClarification);
        summary.put("wholeRulebookNegative", wholeRulebookNegative);
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(summary).containsEntry("sameVersionOnly", true);
        assertThat(run.audited().rawAnswerProviderResponses).isNotEmpty();
        if (phase.startsWith("after")) {
            assertThat(run.answer().status().publishesConclusion()).isTrue();
            assertThat(localUncertainty).isTrue();
            assertThat(usefulClarification).isTrue();
            assertThat(wholeRulebookNegative).isFalse();
            assertThat(summary).containsEntry("prosePreserved", true);
        }
    }

    @Test
    void recordsOnePaidFieldLocalRepairWithRawDomainAndPlayerVisibleEvidence() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_REPAIR_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(
                ".local/agent-evaluation/answer-player-repair-canary-" + phase + "-raw.json");
        Path summaryOutput = root.resolve(
                ".local/agent-evaluation/answer-player-repair-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "repair canary evidence already exists; choose a new phase label instead of overwriting it");

        UUID versionId = UUID.nameUUIDFromBytes(
                ("answer-player-repair:" + phase).getBytes(StandardCharsets.UTF_8));
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(rulebook, versionId);
        HybridEvidenceHit evidence = corpus.hit(2);
        String question = "达到多少胜利点会赢？规则书有没有保证获胜的最佳开局？";
        ModelRequest request = modelRequest(
                question,
                List.of(evidence),
                PlayerLocale.ZH_CN,
                Set.of(EvidenceNeed.DIRECT_RULE, EvidenceNeed.ADVICE));
        ModelDraft previous = new ModelDraft(
                "达到30点胜利点即可获胜。",
                "当前规则页说明，达到30点胜利点即可获胜。规则书未提供保证获胜的最佳开局。",
                List.of(evidence.evidence().chunkId()),
                List.of(),
                "HIGH");
        AnswerPlayerFacingRepairPolicy.RepairPlan repairPlan =
                AnswerPlayerFacingRepairPolicy.planFor(request, previous);
        assertThat(repairPlan.editableFields()).containsExactly(PlayerFacingField.EXPLANATION);

        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        AnswerModelGateway gateway = new AnswerModelGateway(answerModel(configured, audited), limiter, audited);
        long started = System.nanoTime();
        ModelDraft repaired = gateway.revisePlayerFacing(
                UUID.randomUUID(),
                "agent-evaluation",
                null,
                request,
                previous,
                repairPlan.feedback(),
                repairPlan.editableFields(),
                "repairPlayerFacingRuleAnswer",
                "Player-facing source scope repaired");
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        AnswerDraftPublicationPolicy.Preparation preparation =
                AnswerDraftPublicationPolicy.prepare(request, repaired);
        assertThat(preparation.ready())
                .as("repair must pass the deterministic publication boundary; failure=%s, draft=%s",
                        preparation.failureMessage(), repaired)
                .isTrue();
        StructuredRuleAnswer published = publishBoundaryAnswer(
                versionId, request, preparation.draft(), List.of(evidence));
        PlayerFacingRuleAnswer player = PlayerFacingAnswerPresenter.present(
                published, question, PlayerLocale.ZH_CN);
        String providerText = (String) audited.rawAnswerProviderResponses.getLast().get("text");
        JsonNode providerPatch = mapper.readTree(providerText);
        String providerExplanation = providerPatch.path("explanation").asText();
        Set<String> providerFields = new LinkedHashSet<>();
        providerPatch.fieldNames().forEachRemaining(providerFields::add);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("provider", configured.provider());
        raw.put("model", configured.model());
        raw.put("question", question);
        raw.put("evidencePage", evidence.evidence().pageFrom());
        raw.put("previousDraft", previous);
        raw.put("editableFields", repairPlan.editableFields());
        raw.put("repairFeedback", repairPlan.feedback());
        raw.put("providerResponses", List.copyOf(audited.rawAnswerProviderResponses));
        raw.put("promptSizes", List.copyOf(audited.answerPromptSizes));
        raw.put("mergedDomainDrafts", List.copyOf(audited.rawAnswerDrafts));
        raw.put("publishedAnswer", visibleAnswer(published));
        raw.put("playerVisibleAnswer", player);
        Files.writeString(
                rawOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        boolean localBoundary = containsLocalizedEvidenceBoundary(repaired.explanation());
        boolean wholeRulebookNegative = containsWholeRulebookNegative(repaired.explanation());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("editableFields", repairPlan.editableFields());
        summary.put("modelCalls", audited.modelCalls);
        summary.put("providerModelCalls", audited.answerPromptSizes.size());
        summary.put("toolCalls", audited.toolCalls);
        summary.put("latencyMs", latencyMs);
        summary.put("sameAgentRepair", true);
        summary.put("providerReturnedOnlyEditableField", providerFields.equals(Set.of("explanation")));
        summary.put("providerRawPatchToDomainExplanationExact",
                providerExplanation.equals(repaired.explanation()));
        summary.put("lockedVerdictPreserved", previous.shortVerdict().equals(repaired.shortVerdict()));
        summary.put("lockedCitationsPreserved", previous.citationIds().equals(repaired.citationIds()));
        summary.put("lockedMetadataPreserved", previous.confidence().equals(repaired.confidence())
                && previous.answerBasis().equals(repaired.answerBasis()));
        summary.put("domainToPublishedCoreExact", repaired.shortVerdict().equals(published.shortVerdict())
                && repaired.explanation().equals(published.explanation()));
        summary.put("publishedToPlayerCoreExact", published.shortVerdict().equals(player.shortVerdict())
                && published.explanation().equals(player.explanation()));
        summary.put("localizedEvidenceBoundary", localBoundary);
        summary.put("wholeRulebookNegative", wholeRulebookNegative);
        summary.put("publishedAnswer", visibleAnswer(published));
        summary.put("playerVisibleAnswer", player);
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(published.status().publishesConclusion()).isTrue();
        assertThat(repaired.shortVerdict()).isEqualTo(previous.shortVerdict());
        assertThat(repaired.citationIds()).isEqualTo(previous.citationIds());
        assertThat(providerExplanation).isEqualTo(repaired.explanation());
        assertThat(repaired.explanation()).contains("30");
        assertThat(localBoundary).isTrue();
        assertThat(wholeRulebookNegative).isFalse();
        assertThat(audited.modelCalls).isEqualTo(1);
        assertThat(audited.toolCalls).isZero();
        assertThat(audited.answerPromptSizes).singleElement().satisfies(prompt ->
                assertThat((Integer) prompt.get("systemChars")).isLessThan(2_500));
        assertThat(published.shortVerdict()).isEqualTo(player.shortVerdict());
        assertThat(published.explanation()).isEqualTo(player.explanation());
    }

    @Test
    void recordsOnePaidDirectRuleAnswerCanaryWithoutOverwritingPriorEvidence() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_SCENARIO_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-direct-canary-" + phase + "-raw.jsonl");
        Path summaryOutput = root.resolve(".local/agent-evaluation/answer-direct-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "direct canary evidence already exists; choose a new phase label instead of overwriting it");

        String question = "这款游戏有哪两种获胜方式？";
        long started = System.nanoTime();
        BoundaryAnswer run = runBoundaryAnswer(
                rulebook,
                configured,
                question,
                2,
                rawOutput,
                "direct-victory-rule");
        long wallLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Map<String, Object> summary = new LinkedHashMap<>(boundarySummary(run));
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("publishedAnswer", visibleAnswer(run.answer()));
        summary.put("wallLatencyMs", wallLatencyMs);
        String visible = visibleText(run.answer());
        summary.put("naturalAnswerChars", visible.length());
        summary.put("containsLocalAbstention", visible.contains("当前摘录") || visible.contains("无法确认"));
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(run.answer().status().publishesConclusion()).isTrue();
        assertThat(run.plan().evidenceNeeds())
                .contains(EvidenceNeed.DIRECT_RULE, EvidenceNeed.COMPLETE_LIST)
                .doesNotContain(EvidenceNeed.ADVICE);
        assertThat(run.answer().citations()).anyMatch(citation -> citation.pageFrom() == 2);
        assertThat(visible).contains("30").containsAnyOf("统治卡", "dominance");
        assertThat(visible.length()).isBetween(80, 1_700);
        assertThat(summary).containsEntry("prosePreserved", true);
        assertThat(run.audited().modelCalls).isLessThanOrEqualTo(3);
        assertThat(run.audited().rawAnswerDrafts).hasSize(1);
    }

    @Test
    void recordsOnePaidStraightClauseAnswerWithTwoProviderCallsAndNoToolLoop() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_SCENARIO_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-straight-canary-" + phase + "-raw.jsonl");
        Path summaryOutput = root.resolve(".local/agent-evaluation/answer-straight-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "straight canary evidence already exists; choose a new phase label instead of overwriting it");

        String question = "达到多少胜利点会赢？";
        long started = System.nanoTime();
        BoundaryAnswer run = runBoundaryAnswer(
                rulebook,
                configured,
                question,
                2,
                rawOutput,
                "straight-victory-threshold");
        long wallLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        PlayerFacingRuleAnswer player = PlayerFacingAnswerPresenter.present(
                run.answer(), question, PlayerLocale.ZH_CN);

        Map<String, Object> summary = new LinkedHashMap<>(boundarySummary(run));
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("publishedAnswer", visibleAnswer(run.answer()));
        summary.put("playerVisibleAnswer", player);
        summary.put("wallLatencyMs", wallLatencyMs);
        summary.put("playerVisibleCorePreserved", run.answer().shortVerdict().equals(player.shortVerdict())
                && run.answer().explanation().equals(player.explanation()));
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(run.answer().status().publishesConclusion()).isTrue();
        assertThat(run.plan().evidenceNeeds()).contains(EvidenceNeed.DIRECT_RULE).doesNotContain(EvidenceNeed.ADVICE);
        assertThat(visibleText(run.answer())).contains("30");
        assertThat(run.audited().rawNativeTurns).isEmpty();
        assertThat(run.audited().toolCalls).isZero();
        assertThat(run.audited().rawAnswerDrafts).hasSize(1);
        assertThat(run.audited().answerPromptSizes).hasSize(2);
        assertThat(run.audited().modelCalls).isEqualTo(2);
        assertThat(summary)
                .containsEntry("providerModelCalls", 2)
                .containsEntry("sameAgentAnswerRepairs", 0)
                .containsEntry("prosePreserved", true)
                .containsEntry("providerRawToPublishedVerdictPreserved", true)
                .containsEntry("providerRawToPublishedExplanationPreserved", true)
                .containsEntry("playerVisibleCorePreserved", true);
    }

    @Test
    void recordsOnePaidAmbiguousFollowUpCanaryWithoutStartingRetrievalOrComposition() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_SCENARIO_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-ambiguity-canary-" + phase + "-raw.json");
        Path summaryOutput = root.resolve(".local/agent-evaluation/answer-ambiguity-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "ambiguity canary evidence already exists; choose a new phase label instead of overwriting it");
        ProviderConfiguration configured = provider("deepseek");
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        AnswerModelGateway gateway = new AnswerModelGateway(answerModel(configured, audited), limiter, audited);
        UUID versionId = UUID.nameUUIDFromBytes(
                ("answer-ambiguity:" + phase).getBytes(StandardCharsets.UTF_8));
        String question = "这个什么时候触发？";
        UnderstoodQuestion deterministic = question(versionId, question);
        QuestionContext context = new QuestionContext(versionId, null, null, PlayerLocale.ZH_CN);

        long started = System.nanoTime();
        QuestionInterpretationDraft providerDraft = gateway
                .interpretQuestion(
                        UUID.randomUUID(),
                        "agent-evaluation",
                        null,
                        new QuestionInterpretationRequest(
                                question,
                                "",
                                "",
                                "",
                                deterministic.type(),
                                deterministic.missingContext(),
                                null,
                                PlayerLocale.ZH_CN))
                .orElseThrow(() -> new AssertionError("provider did not return an ambiguity interpretation"));
        AnswerQuestionInterpretationPolicy.Interpretation interpretation =
                new AnswerQuestionInterpretationPolicy()
                        .applyWithPlan(deterministic, context, providerDraft)
                        .orElseThrow(() -> new AssertionError("ambiguity interpretation failed deterministic policy"));
        StructuredRuleAnswer answer = AnswerOutcomePolicy.clarification(
                interpretation.question(), PlayerLocale.ZH_CN);
        PlayerFacingRuleAnswer playerAnswer = PlayerFacingAnswerPresenter.present(
                answer, question, PlayerLocale.ZH_CN);
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("provider", configured.provider());
        raw.put("model", configured.model());
        raw.put("question", question);
        raw.put("providerResponses", List.copyOf(audited.rawAnswerProviderResponses));
        raw.put("promptSizes", List.copyOf(audited.answerPromptSizes));
        raw.put("acceptedInterpretation", providerDraft);
        Files.writeString(
                rawOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("answer", visibleAnswer(answer));
        summary.put("playerVisibleAnswer", playerAnswer);
        summary.put("rawClarificationToPlayerExact", answer.clarification().equals(playerAnswer.clarification()));
        summary.put("modelCalls", audited.modelCalls);
        summary.put("toolCalls", audited.toolCalls);
        summary.put("answerModelDrafts", audited.rawAnswerDrafts.size());
        summary.put("latencyMs", latencyMs);
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(answer.status().name()).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(answer.clarification()).contains("具体指什么", "卡牌", "行动", "效果");
        assertThat(playerAnswer.clarification()).isEqualTo(answer.clarification());
        assertThat(audited.modelCalls).isEqualTo(1);
        assertThat(audited.toolCalls).isZero();
        assertThat(audited.rawAnswerDrafts).isEmpty();
        assertThat(audited.rawNativeTurns).isEmpty();
    }

    @Test
    void recordsThreePaidImaginativeAnswerCanariesWithRawDomainAndPlayerVisibleEvidence() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_IMAGINATIVE_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(
                ".local/agent-evaluation/answer-imaginative-canary-" + phase + "-raw.jsonl");
        Path summaryOutput = root.resolve(
                ".local/agent-evaluation/answer-imaginative-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "imaginative canary evidence already exists; choose a new phase label instead of overwriting it");

        List<ImaginativeAnswerCase> cases = List.of(
                new ImaginativeAnswerCase(
                        "colloquial-false-premise",
                        "哎我是不是记串了：不是攒到20分就立刻赢吗？要是我硬说这是规则，请你先纠错，再告诉我真正的全部获胜方式。",
                        2),
                new ImaginativeAnswerCase(
                        "fact-plus-guaranteed-strategy",
                        "我今晚第一次带朋友玩：先用大白话告诉我每个阵营靠什么得分，再给我一套‘照抄就稳赢’的前三步开局。规则书没写的部分请别装懂。",
                        2),
                new ImaginativeAnswerCase(
                        "roleplay-grounded-scope-and-timing",
                        "请扮演‘林地最高法院’，按【裁决】【理由】【边界】回答：我们只有两名玩家，我已经在开局拿到统治卡，所以现在就能打出并靠它获胜，对吧？请分别判断‘两人能不能用统治卡’‘开局能不能打’‘不靠统治卡正常怎么赢’。",
                        22));

        Map<String, BoundaryAnswer> runs = new LinkedHashMap<>();
        Map<String, PlayerFacingRuleAnswer> playerAnswers = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (ImaginativeAnswerCase case_ : cases) {
            long started = System.nanoTime();
            BoundaryAnswer run = runBoundaryAnswer(
                    rulebook,
                    configured,
                    case_.question(),
                    case_.initialPage(),
                    rawOutput,
                    case_.caseId());
            long wallLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            PlayerFacingRuleAnswer playerAnswer = PlayerFacingAnswerPresenter.present(
                    run.answer(), case_.question(), PlayerLocale.ZH_CN);
            ReviewRisk reviewRisk = run.answer().status().publishesConclusion()
                    ? AnswerCritiquePolicy.reviewRisk(
                            question(run.answer().documentVersionId(), case_.question()),
                            new QuestionContext(run.answer().documentVersionId()),
                            run.modelRequest(),
                            run.answer())
                    : null;
            String playerText = (playerAnswer.shortVerdict() + " " + playerAnswer.explanation()).strip();
            Map<String, Object> result = new LinkedHashMap<>(boundarySummary(run));
            result.put("caseId", case_.caseId());
            result.put("question", case_.question());
            result.put("wallLatencyMs", wallLatencyMs);
            result.put("questionPlan", run.plan());
            result.put("providerRawDraft", run.providerRawDraft());
            result.put("preparedDomainDraft", run.preparedDraft());
            result.put("publishedDomainAnswer", run.answer());
            result.put("playerVisibleAnswer", playerAnswer);
            result.put("playerVisibleCorePreserved", run.answer().shortVerdict().equals(playerAnswer.shortVerdict())
                    && run.answer().explanation().equals(playerAnswer.explanation()));
            result.put("reviewRisk", reviewRisk == null ? "NOT_REVIEWABLE" : reviewRisk.name());
            result.put("fallback", !playerAnswer.status().publishesConclusion());
            result.put("sameVersionOnly", run.refined().evidence().stream()
                    .allMatch(hit -> run.answer().documentVersionId().equals(hit.evidence().documentVersionId())));
            result.put("wholeRulebookNegative", containsWholeRulebookNegative(playerText));
            result.put("localizedEvidenceBoundary", containsLocalizedEvidenceBoundary(playerText));
            result.put("usefulClarification", containsUsefulClarification(playerText));
            results.add(result);
            runs.put(case_.caseId(), run);
            playerAnswers.put(case_.caseId(), playerAnswer);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("provider", configured.provider());
        summary.put("model", configured.model());
        summary.put("results", results);
        summary.put("controls", Map.of(
                "rawEvidenceStoredLocallyOnly", true,
                "productionVocabularySpecialCasesAdded", false,
                "playerVisiblePresenterApplied", true,
                "riskDerivedFromPublishedAnswer", true));
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        BoundaryAnswer falsePremise = runs.get("colloquial-false-premise");
        String falsePremiseText = visibleText(falsePremise.answer());
        assertThat(falsePremise.answer().status().publishesConclusion()).isTrue();
        assertThat(falsePremise.plan().evidenceNeeds())
                .contains(EvidenceNeed.DIRECT_RULE, EvidenceNeed.COMPLETE_LIST);
        assertThat(falsePremise.answer().citations()).anyMatch(citation -> citation.pageFrom() == 2);
        assertThat(falsePremiseText).contains("30").containsAnyOf("统治卡", "支配卡", "dominance");
        assertThat(containsWholeRulebookNegative(falsePremiseText)).isFalse();
        ReviewRisk falsePremiseRisk = AnswerCritiquePolicy.reviewRisk(
                question(falsePremise.answer().documentVersionId(), falsePremise.answer().shortVerdict()),
                new QuestionContext(falsePremise.answer().documentVersionId()),
                falsePremise.modelRequest(),
                falsePremise.answer());
        assertThat(falsePremiseRisk).isEqualTo(
                falsePremise.answer().citations().size() == 1 ? ReviewRisk.STANDARD : ReviewRisk.HIGH_IMPACT);

        BoundaryAnswer mixed = runs.get("fact-plus-guaranteed-strategy");
        String mixedText = visibleText(mixed.answer());
        assertThat(mixed.answer().status().publishesConclusion()).isTrue();
        assertThat(mixed.plan().evidenceNeeds()).contains(EvidenceNeed.DIRECT_RULE, EvidenceNeed.ADVICE);
        assertThat(mixed.answer().citations()).isNotEmpty();
        assertThat(mixedText).containsAnyOf("得分", "胜利点");
        assertThat(containsLocalizedEvidenceBoundary(mixedText)).isTrue();
        assertThat(containsUsefulClarification(mixedText)).isTrue();
        assertThat(containsWholeRulebookNegative(mixedText)).isFalse();

        BoundaryAnswer grounded = runs.get("roleplay-grounded-scope-and-timing");
        PlayerFacingRuleAnswer groundedPlayer = playerAnswers.get("roleplay-grounded-scope-and-timing");
        String groundedText = visibleText(grounded.answer());
        assertThat(grounded.answer().status().publishesConclusion()).isTrue();
        assertThat(groundedPlayer.status().publishesConclusion()).isTrue();
        assertThat(grounded.plan().subquestions()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(grounded.answer().citations())
                .anyMatch(citation -> citation.pageFrom() == 2)
                .anyMatch(citation -> citation.pageFrom() == 21)
                .anyMatch(citation -> citation.pageFrom() == 22);
        assertThat(groundedText)
                .containsAnyOf("两名", "两人", "二人", "2人", "两个玩家")
                .containsAnyOf("开局", "早期")
                .contains("30")
                .contains("裁决", "理由", "边界");
        assertThat(AnswerCritiquePolicy.reviewRisk(
                        question(grounded.answer().documentVersionId(), grounded.answer().shortVerdict()),
                        new QuestionContext(grounded.answer().documentVersionId()),
                        grounded.modelRequest(),
                        grounded.answer()))
                .isEqualTo(ReviewRisk.HIGH_IMPACT);
        assertThat(containsWholeRulebookNegative(groundedText)).isFalse();

        assertThat(results).allSatisfy(result -> assertThat(result)
                .containsEntry("fallback", false)
                .containsEntry("sameVersionOnly", true)
                .containsEntry("playerVisibleCorePreserved", true)
                .containsEntry("prosePreserved", true)
                .containsEntry("providerRawToPublishedVerdictPreserved", true)
                .containsEntry("providerRawToPublishedExplanationPreserved", true));
        assertThat(playerAnswers.values()).allSatisfy(player -> {
            assertThat(player.status().publishesConclusion()).isTrue();
            assertThat(player.citations()).isNotEmpty();
        });
    }

    @Test
    void recordsProductionHighImpactAnswerWithoutASynchronousCritic() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_CRITIC_CANARY")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path answerRawOutput = root.resolve(
                ".local/agent-evaluation/answer-high-impact-critic-" + phase + "-answer-raw.jsonl");
        Path rawOutput = root.resolve(
                ".local/agent-evaluation/answer-high-impact-critic-" + phase + "-raw.json");
        Path summaryOutput = root.resolve(
                ".local/agent-evaluation/answer-high-impact-critic-" + phase + ".json");
        assumeTrue(!Files.exists(answerRawOutput) && !Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "critic canary evidence already exists; choose a new phase label instead of overwriting it");

        String question = "请扮演‘林地最高法院’：我们只有两名玩家，我开局拿到统治卡，现在就能打出并靠它获胜吗？也请说明不靠统治卡怎么赢。";
        BoundaryAnswer run = runBoundaryAnswer(
                rulebook, configured, question, 22, answerRawOutput, "high-impact-scope-timing");
        ReviewRisk risk = AnswerCritiquePolicy.reviewRisk(
                question(run.answer().documentVersionId(), question),
                new QuestionContext(run.answer().documentVersionId()),
                run.modelRequest(),
                run.answer());
        List<String> rawCriticResponses = new ArrayList<>();
        DirectAuditedInvocations criticInvocations = new DirectAuditedInvocations();
        var critic = contentCritic(configured, rawCriticResponses, criticInvocations);
        long started = System.nanoTime();
        var review = critic.review(
                AnswerCritiquePolicy.request(
                        UUID.randomUUID(),
                        question(run.answer().documentVersionId(), question),
                        new QuestionContext(run.answer().documentVersionId()),
                        run.modelRequest(),
                        run.answer(),
                        run.refined().evidence()),
                risk,
                "agent-evaluation");
        long criticLatencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Map<String, Object> summary = new LinkedHashMap<>(boundarySummary(run));
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("reviewRisk", risk.name());
        summary.put("criticPerformed", review.performed());
        summary.put("criticIssues", review.issues());
        summary.put("criticProviderCalls", rawCriticResponses.size());
        summary.put("criticLatencyMs", criticLatencyMs);
        summary.put("totalProviderCalls",
                run.audited().answerPromptSizes.size()
                        + run.audited().rawNativeTurns.size()
                        + rawCriticResponses.size());
        summary.put("productionCriticSkipped", !review.performed() && rawCriticResponses.isEmpty());
        summary.put("candidatePlayerAnswer", PlayerFacingAnswerPresenter.present(
                run.answer(), question, PlayerLocale.ZH_CN));
        Files.writeString(
                rawOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "providerResponses", rawCriticResponses,
                                "review", review))
                        + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        assertThat(run.answer().status().publishesConclusion()).isTrue();
        assertThat(risk).isEqualTo(ReviewRisk.HIGH_IMPACT);
        assertThat(review.performed()).isFalse();
        assertThat(rawCriticResponses).isEmpty();
        assertThat(run.answer().citations())
                .anyMatch(citation -> citation.pageFrom() == 2)
                .anyMatch(citation -> citation.pageFrom() == 21)
                .anyMatch(citation -> citation.pageFrom() == 22);
        assertThat(summary)
                .containsEntry("productionCriticSkipped", true)
                .containsEntry("prosePreserved", true)
                .containsEntry("providerRawToPublishedVerdictPreserved", true)
                .containsEntry("providerRawToPublishedExplanationPreserved", true);
    }

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
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-compound-raw-visible-turns.jsonl");
        Files.createDirectories(rawOutput.getParent());
        Files.writeString(
                rawOutput,
                "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        List<Map<String, Object>> results = new ArrayList<>();
        for (CaseConfiguration case_ : cases) results.add(runCase(case_, rawOutput));
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
    void answersBoundedVisualTranscriptionsAcrossPaidProvidersWithoutOutsideGameKnowledge() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        List<VisualTranscriptionCase> cases = List.of(
                new VisualTranscriptionCase(
                        "visual-transcription-zh",
                        "deepseek",
                        "我从一个公开供应区拿走一种标记后，剩下的标记放哪里？",
                        "玩家从任意公开供应区拿走一种标记的全部副本，然后把该供应区其余标记全部移到桌面中央的公共区。",
                        PlayerLocale.ZH_CN,
                        "公共区"),
                new VisualTranscriptionCase(
                        "visual-transcription-en",
                        "qwen",
                        "After I choose one action card, what happens to the unplayed cards?",
                        "After choosing one action card, place every unplayed card face down in the discard area.",
                        PlayerLocale.EN,
                        "discard"));
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> raw = new ArrayList<>();

        for (VisualTranscriptionCase case_ : cases) {
            ProviderConfiguration configured = provider(case_.provider());
            DirectAuditedInvocations audited = new DirectAuditedInvocations();
            SpringAiRuleAnswerModel model = answerModel(configured, audited);
            RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
            when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
            AnswerModelGateway gateway = new AnswerModelGateway(model, limiter, audited);
            UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
            RuleEvidenceHit source = new RuleEvidenceHit(
                    UUID.randomUUID(),
                    versionId,
                    "VISUAL_TRANSCRIPTION",
                    "Rendered rulebook page",
                    PageFact.transcribedRuleEvidenceText(case_.factualSummary()),
                    3,
                    3,
                    1.0);
            HybridEvidenceHit evidence = new HybridEvidenceHit(source, 1.0, 1, null, false);
            ModelRequest request = modelRequest(
                    case_.question(), List.of(evidence), case_.locale(), Set.of(EvidenceNeed.DIRECT_RULE));
            long started = System.nanoTime();
            AnswerDraftComposer.Result prepared = new AnswerDraftComposer(gateway)
                    .compose(UUID.randomUUID(), "agent-evaluation", null, request);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(prepared.ready())
                    .as(configured.provider() + " should use a bounded image-page transcription as supplied evidence")
                    .isTrue();
            StructuredRuleAnswer answer = new AnswerPublicationValidator(new PolicyEvidenceVerifier())
                    .publish(versionId, prepared.draft(), List.of(evidence));
            assertThat(answer.status().publishesConclusion()).isTrue();
            assertThat(answer.citations()).singleElement().satisfies(citation -> {
                assertThat(citation.pageFrom()).isEqualTo(3);
                assertThat(citation.excerpt()).isEqualTo(case_.factualSummary());
            });
            assertThat(visibleText(answer).toLowerCase(Locale.ROOT)).contains(case_.expectedTerm().toLowerCase(Locale.ROOT));

            results.add(Map.of(
                    "caseId", case_.caseId(),
                    "provider", configured.provider(),
                    "model", configured.model(),
                    "status", answer.status().name(),
                    "citationPages", answer.citations().stream().map(citation -> citation.pageFrom()).toList(),
                    "latencyMs", latencyMs,
                    "withinLatencyBudget", latencyMs < 120_000));
            raw.add(Map.of(
                    "caseId", case_.caseId(),
                    "providerResponses", List.copyOf(audited.rawAnswerProviderResponses),
                    "visibleDrafts", List.copyOf(audited.rawAnswerDrafts),
                    "publishedAnswer", visibleAnswer(answer)));
        }

        Path output = root.resolve(".local/agent-evaluation/answer-visual-transcription-real.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "imageBytesGivenToAnswerModel", false,
                        "pageScopedVisualLedgerOnly", true,
                        "outsideGameKnowledgeAllowed", false))) + "\n", StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve(".local/agent-evaluation/answer-visual-transcription-raw-visible.json").toFile().toPath(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void distinguishesWinConditionsFromSourcedAndUnsupportedStrategyAdviceAcrossPaidProviders() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_ANSWER_AGENT_EVAL")));
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rootRulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        Path unsupportedAdviceRulebook = rulebookForTitle(root, inventory, "Atelier: The Painter's Studio");
        Path rawOutput = root.resolve(".local/agent-evaluation/answer-raw-visible-turns.jsonl");
        Files.createDirectories(rawOutput.getParent());
        Files.writeString(
                rawOutput,
                "",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        List<ProviderBoundaryRuns> providerRuns = new ArrayList<>();
        for (String providerName : List.of("deepseek", "qwen")) {
            ProviderConfiguration configured = provider(providerName);
            BoundaryAnswer win = runBoundaryAnswer(
                    rootRulebook,
                    configured,
                    "这款游戏我怎么赢？",
                    24,
                    rawOutput,
                    "win-condition");
            BoundaryAnswer sourcedStrategy = runBoundaryAnswer(
                    rootRulebook,
                    configured,
                    "有没有赢的策略？",
                    3,
                    rawOutput,
                    "strategy-advice-present");
            BoundaryAnswer unsupportedStrategy = runBoundaryAnswer(
                    unsupportedAdviceRulebook,
                    configured,
                    "有没有更容易赢的打法或建议？",
                    2,
                    rawOutput,
                    "strategy-advice-absent");
            providerRuns.add(new ProviderBoundaryRuns(configured, win, sourcedStrategy, unsupportedStrategy));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (ProviderBoundaryRuns runs : providerRuns) {
            ProviderConfiguration configured = runs.provider();
            String providerName = configured.provider();
            BoundaryAnswer win = runs.winCondition();
            BoundaryAnswer sourcedStrategy = runs.sourcedStrategy();
            BoundaryAnswer unsupportedStrategy = runs.unsupportedStrategy();
            assertThat(win.refined().evidence())
                    .as(providerName + " must acquire the canonical victory page")
                    .anyMatch(hit -> hit.evidence().pageFrom() == 2);
            assertThat(win.plan().evidenceNeeds())
                    .as(providerName + " must distinguish victory conditions from strategy advice")
                    .contains(EvidenceNeed.DIRECT_RULE, EvidenceNeed.COMPLETE_LIST)
                    .doesNotContain(EvidenceNeed.ADVICE);
            assertThat(win.answer().status().publishesConclusion())
                    .as(providerName + " must answer the evidenced win condition")
                    .isTrue();
            assertThat(win.answer().citations())
                    .as(providerName + " must cite the direct victory page")
                    .anyMatch(citation -> citation.pageFrom() == 2);
            assertThat(visibleText(win.answer()))
                    .as(providerName + " must preserve both stated victory routes")
                    .contains("30")
                    .containsAnyOf("card", "Card", "卡", "牌");

            assertThat(sourcedStrategy.plan().evidenceNeeds())
                    .as(providerName + " must semantically plan an advice-evidence obligation")
                    .contains(EvidenceNeed.ADVICE);
            assertThat(sourcedStrategy.answer().status().publishesConclusion())
                    .as(providerName + " must answer source-authored bounded strategy advice")
                    .isTrue();
            assertThat(sourcedStrategy.answer().citations())
                    .as(providerName + " must cite the page that actually supplies strategy guidance")
                    .anyMatch(citation -> citation.pageFrom() == 22);
            assertThat(visibleText(sourcedStrategy.answer()))
                    .as(providerName + " must preserve the advice's faction or player-count scope")
                    .containsAnyOf(
                            "Eyrie", "Marquise", "Alliance", "Vagabond",
                            "鹰巢", "侯爵", "联盟", "流浪者");

            assertThat(unsupportedStrategy.plan().evidenceNeeds())
                    .as(providerName + " must apply the same semantic advice obligation across rulebooks")
                    .contains(EvidenceNeed.ADVICE);
            assertThat(unsupportedStrategy.answer().status())
                    .as(providerName + " must not turn objective or scoring mechanics into unsupported advice")
                    .isEqualTo(com.rulepilot.assistant.domain.AnswerStatus.INSUFFICIENT_EVIDENCE);
            assertThat(unsupportedStrategy.answer().citations()).isEmpty();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", providerName);
            result.put("model", configured.model());
            result.put("winCondition", boundarySummary(win));
            result.put("sourcedStrategyAdvice", boundarySummary(sourcedStrategy));
            result.put("unsupportedStrategyAdvice", boundarySummary(unsupportedStrategy));
            results.add(Map.copyOf(result));
        }

        Path output = root.resolve(".local/agent-evaluation/answer-boundary-real.json");
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "results", results,
                "controls", Map.of(
                        "rawModelOutputStored", false,
                        "separateLocalRawVisibleDiagnostics", true,
                        "hiddenReasoningStored", false,
                        "strategyRequiresSuppliedAdviceEvidence", true))) + "\n", StandardCharsets.UTF_8);
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

    private BoundaryAnswer runBoundaryAnswer(
            Path rulebook,
            ProviderConfiguration provider,
            String playerQuestion,
            int initialPage,
            Path rawOutput,
            String caseId) throws Exception {
        return runBoundaryAnswer(
                rulebook, provider, playerQuestion, initialPage, rawOutput, caseId, null);
    }

    private BoundaryAnswer runBoundaryAnswer(
            Path rulebook,
            ProviderConfiguration provider,
            String playerQuestion,
            int initialPage,
            Path rawOutput,
            String caseId,
            PriorTurnScenario priorScenario) throws Exception {
        UUID versionId = UUID.nameUUIDFromBytes(
                ("answer-boundary:" + provider.provider() + ':' + caseId).getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(rulebook, versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        SpringAiRuleAnswerModel model = answerModel(provider, audited);
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        AnswerModelGateway modelGateway = new AnswerModelGateway(model, limiter, audited);
        UnderstoodQuestion deterministic = question(versionId, playerQuestion);
        PlayerLocale outputLanguage = PlayerLocale.forQuestion(playerQuestion, PlayerLocale.ZH_CN);
        PriorTurnReference priorTurn = priorScenario == null
                ? null
                : new PriorTurnReference(
                        versionId,
                        priorScenario.question(),
                        priorScenario.groundedVerdict(),
                        List.of(new PriorCitationReference(
                                corpus.hit(priorScenario.citationPage()).evidence().chunkId(),
                                versionId,
                                priorScenario.citationPage(),
                                priorScenario.citationPage())));
        QuestionContext suppliedContext = priorScenario == null
                ? new QuestionContext(versionId, null, null, outputLanguage)
                : new QuestionContext(versionId, priorScenario.question(), null, outputLanguage, priorTurn);
        AnswerQuestionPlan plan = AnswerQuestionPlan.fallback(deterministic);
        UnderstoodQuestion understood = deterministic;
        LearningIntent plannedLearningIntent = suppliedContext.learningIntent();
        var interpreted = modelGateway
                .interpretQuestion(
                        runId,
                        "agent-evaluation",
                        null,
                        new QuestionInterpretationRequest(
                                playerQuestion,
                                suppliedContext.previousQuestion(),
                                priorTurn == null ? "" : priorTurn.question(),
                                priorTurn == null ? "" : priorTurn.groundedVerdict(),
                                deterministic.type(),
                                deterministic.missingContext(),
                                null,
                                outputLanguage))
                .flatMap(draft -> new AnswerQuestionInterpretationPolicy()
                        .applyWithPlan(deterministic, suppliedContext, draft));
        if (interpreted.isPresent()) {
            AnswerQuestionInterpretationPolicy.Interpretation accepted = interpreted.orElseThrow();
            understood = accepted.question();
            if (accepted.plan() != null) plan = accepted.plan();
            plannedLearningIntent = accepted.learningIntent();
        }
        QuestionContext questionContext = suppliedContext.withLearningIntent(plannedLearningIntent);
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent evidenceAgent = answerAgent(provider, corpus, audited, scope);
        long started = System.nanoTime();
        AnswerEvidenceRetriever.Result refined = evidenceAgent.refine(
                runId,
                understood,
                questionContext,
                "agent-evaluation",
                null,
                plan,
                ready(corpus.hit(initialPage)));

        ModelRequest request = null;
        AnswerDraftComposer.Result prepared = null;
        StructuredRuleAnswer answer;
        AnswerEvidenceAdmissionGate.Admission admission = new AnswerEvidenceAdmissionGate(
                        new AnswerPublicationValidator(new PolicyEvidenceVerifier()))
                .admit(versionId, refined);
        if (!admission.ready()) {
            answer = AnswerOutcomePolicy.safeFailure(
                    versionId, admission.failureStatus(), admission.failureMessage());
        } else {
            request = new AnswerModelRequestFactory()
                    .create(understood, questionContext, admission.evidence(), plan);
            prepared = new AnswerDraftComposer(modelGateway)
                    .compose(runId, "agent-evaluation", null, request);
            if (!prepared.ready()) {
                answer = AnswerOutcomePolicy.safeFailure(
                        versionId, prepared.failureStatus(), prepared.failureMessage());
            } else {
                try {
                    answer = publishBoundaryAnswer(versionId, request, prepared.draft(), admission.evidence());
                } catch (RuntimeException invalidDraft) {
                    answer = AnswerOutcomePolicy.safeFailure(
                            versionId,
                            com.rulepilot.assistant.domain.AnswerStatus.INVALID_MODEL_OUTPUT,
                            "The generated answer did not satisfy the publication schema.");
                }
            }
        }
        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("caseId", caseId);
        raw.put("provider", provider.provider());
        raw.put("model", provider.model());
        raw.put("question", playerQuestion);
        raw.put("priorTurn", priorScenario);
        raw.put("answerProviderVisibleResponses", List.copyOf(audited.rawAnswerProviderResponses));
        raw.put("answerPromptSizes", List.copyOf(audited.answerPromptSizes));
        raw.put("questionInterpretations", List.copyOf(audited.rawQuestionInterpretations));
        raw.put("acceptedEvidenceNeeds", plan.evidenceNeeds());
        raw.put("nativeVisibleTurns", List.copyOf(audited.rawNativeTurns));
        raw.put("nativeToolTrace", List.copyOf(audited.trace));
        raw.put("nativeRunReason", audited.lastRunReason);
        raw.put("refinedEvidencePages", refined.evidence().stream()
                .map(hit -> hit.evidence().pageFrom())
                .distinct()
                .toList());
        raw.put("answerVisibleDrafts", List.copyOf(audited.rawAnswerDrafts));
        raw.put("publishedStatus", answer.status().name());
        Files.writeString(
                rawOutput,
                mapper.writeValueAsString(raw) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        return new BoundaryAnswer(
                plan,
                request,
                refined,
                answer,
                prepared != null && prepared.ready() ? prepared.draft() : null,
                audited.lastAnswerDraft,
                audited,
                latencyMs);
    }

    /** Uses the same selected-aid resolvers as the production publication boundary. */
    private StructuredRuleAnswer publishBoundaryAnswer(
            UUID versionId,
            ModelRequest request,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence) {
        return new AnswerPublicationValidator(new PolicyEvidenceVerifier()).publish(
                versionId,
                draft,
                evidence,
                new AnswerCalculationResolver().resolve(request, draft),
                new AnswerSituationCheckResolver().resolve(request, draft),
                new AnswerWalkthroughResolver().resolve(request, draft),
                new AnswerDecisionTableResolver().resolve(request, draft),
                new AnswerExceptionClauseResolver().resolve(request, draft),
                new AnswerTermDefinitionResolver().resolve(request, draft),
                new AnswerWorkedExampleResolver().resolve(request, draft),
                new AnswerRulePriorityResolver().resolve(request, draft),
                new AnswerTimingResolver().resolve(request, draft),
                new AnswerTieResolver().resolve(request, draft),
                new AnswerScopeResolver().resolve(request, draft),
                new AnswerConceptComparisonResolver().resolve(request, draft),
                new AnswerRuleOptionResolver().resolve(request, draft));
    }

    private Map<String, Object> boundarySummary(BoundaryAnswer run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("answer", visibleAnswer(run.answer()));
        summary.put("evidenceNeeds", run.plan().evidenceNeeds());
        summary.put("evidencePages", run.refined().evidence().stream()
                .map(hit -> hit.evidence().pageFrom())
                .distinct()
                .toList());
        summary.put("nativeModelTurns", run.audited().rawNativeTurns.size());
        summary.put("questionInterpretations", run.audited().rawQuestionInterpretations.size());
        summary.put("answerModelDrafts", run.audited().rawAnswerDrafts.size());
        summary.put("sameAgentAnswerRepairs", Math.max(0, run.audited().rawAnswerDrafts.size() - 1));
        summary.put("answerPromptSizes", List.copyOf(run.audited().answerPromptSizes));
        summary.put("modelCalls", run.audited().modelCalls);
        summary.put("providerModelCalls", run.audited().answerPromptSizes.size()
                + run.audited().rawNativeTurns.size());
        long interpretationPrompts = run.audited().answerPromptSizes.stream()
                .filter(prompt -> "INTERPRETATION".equals(prompt.get("kind")))
                .count();
        summary.put("interpretationContractRepairs", Math.max(
                0L, interpretationPrompts - run.audited().rawQuestionInterpretations.size()));
        summary.put("toolCalls", run.audited().toolCalls);
        summary.put("latencyMs", run.latencyMs());
        summary.put("withinLatencyBudget", run.latencyMs() < 120_000);
        summary.put("answerBasis", run.answer().answerBasis() == null
                ? "NONE"
                : run.answer().answerBasis().name());
        if (run.preparedDraft() != null && run.answer().status().publishesConclusion()) {
            summary.put("rawVerdictChars", run.preparedDraft().shortVerdict().length());
            summary.put("publishedVerdictChars", run.answer().shortVerdict().length());
            summary.put("rawExplanationChars", run.preparedDraft().explanation().length());
            summary.put("publishedExplanationChars", run.answer().explanation().length());
            summary.put("rawStructuredItems", structuredItems(run.preparedDraft()));
            summary.put("publishedStructuredItems", structuredItems(run.answer()));
            summary.put("prosePreserved", run.preparedDraft().shortVerdict().equals(run.answer().shortVerdict())
                    && run.preparedDraft().explanation().equals(run.answer().explanation()));
        }
        if (run.providerRawDraft() != null && run.answer().status().publishesConclusion()) {
            summary.put("providerRawVerdictChars", run.providerRawDraft().shortVerdict().length());
            summary.put("providerRawExplanationChars", run.providerRawDraft().explanation().length());
            summary.put("providerRawToPublishedVerdictPreserved",
                    run.providerRawDraft().shortVerdict().equals(run.answer().shortVerdict()));
            summary.put("providerRawToPublishedExplanationPreserved",
                    run.providerRawDraft().explanation().equals(run.answer().explanation()));
        }
        return Map.copyOf(summary);
    }

    private int structuredItems(ModelDraft draft) {
        return draft.calculations().size()
                + draft.situationChecks().size()
                + draft.walkthroughSteps().size()
                + draft.decisionBranches().size()
                + draft.exceptionClauses().size()
                + draft.termDefinitions().size()
                + draft.workedExamples().size()
                + draft.priorityResolutions().size()
                + draft.timingResolutions().size()
                + draft.tieResolutions().size()
                + draft.scopeResolutions().size()
                + draft.conceptComparisons().size()
                + draft.ruleOptions().size()
                + draft.exceptions().size();
    }

    private int structuredItems(StructuredRuleAnswer answer) {
        return answer.calculations().size()
                + answer.situationChecks().size()
                + answer.walkthroughSteps().size()
                + answer.decisionBranches().size()
                + answer.exceptionClauses().size()
                + answer.termDefinitions().size()
                + answer.workedExamples().size()
                + answer.priorityResolutions().size()
                + answer.timingResolutions().size()
                + answer.tieResolutions().size()
                + answer.scopeResolutions().size()
                + answer.conceptComparisons().size()
                + answer.ruleOptions().size()
                + answer.exceptions().size();
    }

    private ModelRequest modelRequest(String question, List<HybridEvidenceHit> evidence) {
        return modelRequest(question, evidence, PlayerLocale.EN);
    }

    private ModelRequest modelRequest(
            String question, List<HybridEvidenceHit> evidence, PlayerLocale outputLanguage) {
        return modelRequest(question, evidence, outputLanguage, Set.of(EvidenceNeed.DIRECT_RULE));
    }

    private ModelRequest modelRequest(
            String question,
            List<HybridEvidenceHit> evidence,
            PlayerLocale outputLanguage,
            Set<EvidenceNeed> evidenceNeeds) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, outputLanguage),
                evidence.stream().map(hit -> new EvidenceInput(
                        hit.evidence().chunkId(),
                        hit.evidence().sectionType(),
                        hit.evidence().heading(),
                        hit.evidence().excerpt(),
                        hit.evidence().pageFrom(),
                        hit.evidence().pageTo())).toList(),
                evidenceNeeds);
    }

    private ModelRequest modelRequest(
            String question,
            List<HybridEvidenceHit> evidence,
            PlayerLocale outputLanguage,
            AnswerQuestionPlan plan) {
        return new ModelRequest(
                question,
                QuestionType.RULE_QUERY,
                new AnswerContext(null, null, outputLanguage),
                evidence.stream().map(hit -> new EvidenceInput(
                        hit.evidence().chunkId(),
                        hit.evidence().sectionType(),
                        hit.evidence().heading(),
                        hit.evidence().excerpt(),
                        hit.evidence().pageFrom(),
                        hit.evidence().pageTo())).toList(),
                plan.evidenceNeeds(),
                plan.answerAid(),
                plan.subquestions().stream()
                        .map(subquestion -> new com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion(
                                subquestion.text(), subquestion.evidenceNeeds()))
                        .toList());
    }

    private StructuredRuleAnswer publishIfReady(
            UUID versionId,
            ModelRequest request,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence) {
        if (!draft.answerable()) {
            return AnswerOutcomePolicy.safeFailure(
                    versionId,
                    com.rulepilot.assistant.domain.AnswerStatus.INSUFFICIENT_EVIDENCE,
                    draft.insufficiencyReason() == null
                            ? "The supplied evidence is insufficient."
                            : draft.insufficiencyReason());
        }
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
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("status", answer.status().name());
        visible.put("shortVerdict", answer.shortVerdict());
        visible.put("explanation", answer.explanation());
        visible.put("citationPages", answer.citations().stream()
                .map(citation -> citation.pageFrom())
                .distinct()
                .toList());
        if (answer.clarification() != null && !answer.clarification().isBlank()) {
            visible.put("clarification", answer.clarification());
        }
        return Map.copyOf(visible);
    }

    private String visibleText(StructuredRuleAnswer answer) {
        return (answer.shortVerdict() + " " + answer.explanation()).replaceAll("\\s+", " ").strip();
    }

    private boolean containsWholeRulebookNegative(String prose) {
        String normalized = prose == null ? "" : prose.toLowerCase(Locale.ROOT);
        return List.of(
                        "规则书没有",
                        "规则书中没有",
                        "规则书里没有",
                        "规则书未提供",
                        "规则书未提及",
                        "规则书未提到",
                        "规则书未说明",
                        "规则书不包含",
                        "规则书只描述",
                        "规则书只说明",
                        "the rulebook does not",
                        "the rulebook doesn't",
                        "the rulebook has no",
                        "the rulebook provides no",
                        "the rulebook contains no")
                .stream()
                .anyMatch(normalized::contains);
    }

    private boolean containsLocalizedEvidenceBoundary(String prose) {
        String normalized = prose == null ? "" : prose.toLowerCase(Locale.ROOT);
        boolean local = normalized.contains("当前")
                || normalized.contains("现有摘录")
                || normalized.contains("这些摘录")
                || normalized.contains("currently supplied")
                || normalized.contains("current excerpts");
        boolean uncertain = normalized.contains("无法确认")
                || normalized.contains("不能确认")
                || normalized.contains("不足以确认")
                || normalized.contains("无法从现有")
                || normalized.contains("不能从现有")
                || normalized.contains("无法从当前")
                || normalized.contains("不能从当前")
                || normalized.contains("无法从这些摘录")
                || normalized.contains("不能从这些摘录")
                || normalized.contains("cannot confirm")
                || normalized.contains("not enough to confirm");
        return local && uncertain;
    }

    private boolean containsUsefulClarification(String prose) {
        String normalized = prose == null ? "" : prose.toLowerCase(Locale.ROOT);
        return normalized.contains("？")
                || normalized.contains("?")
                || normalized.contains("如果你能告诉我")
                || normalized.contains("如果你能指出")
                || normalized.contains("你希望我继续核对")
                || normalized.contains("请告诉我具体")
                || normalized.contains("which specific")
                || normalized.contains("if you can name");
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
                        LearningIntent.EXAMPLE,
                        "example"),
                new DialogueIntentCase(
                        "cx-source-deepseek",
                        "deepseek",
                        "这个行动为什么会立刻结束？",
                        "这条规则在规则书哪里？我想自己翻一下。",
                        PlayerLocale.ZH_CN,
                        LearningIntent.SOURCE,
                        "source"),
                new DialogueIntentCase(
                        "cx-adapt-qwen",
                        "qwen",
                        "这个行动的费用什么时候支付？",
                        "还是有点绕，能先用更简单的话重新讲一遍吗？",
                        PlayerLocale.ZH_CN,
                        LearningIntent.SIMPLIFY,
                        "simplify"),
                new DialogueIntentCase(
                        "cx-exception-qwen",
                        "qwen",
                        "执行完这个步骤后回合就结束吗？",
                        "那有没有例外情况？",
                        PlayerLocale.ZH_CN,
                        LearningIntent.EXCEPTIONS,
                        "exception"));
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
                            case_.caseId() + "/" + case_.provider()
                                    + " did not return a valid semantic teaching plan"));
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(draft.referenceBinding())
                    .as(case_.caseId() + " must bind the supplied previous question")
                    .isEqualTo(ReferenceBinding.PREVIOUS_QUESTION);
            assertThat(draft.learningIntent())
                    .as(case_.caseId() + " must infer the natural teaching move")
                    .isEqualTo(case_.expectedIntent());
            assertThat(draft.subquestions()).as(case_.caseId() + " must plan fresh evidence").isNotEmpty();
            assertThat(latencyMs).isLessThan(45_000);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("caseId", case_.caseId());
            result.put("provider", case_.provider());
            result.put("model", configured.model());
            result.put("referenceBinding", draft.referenceBinding().name());
            result.put("learningIntent", draft.learningIntent().name());
            result.put("interactionTag", case_.interactionTag());
            result.put("naturalTurnCount", 2);
            result.put("subquestionCount", draft.subquestions().size());
            result.put("modelCalls", 1);
            result.put("toolCalls", 0);
            result.put("latencyMs", latencyMs);
            results.add(Map.copyOf(result));
        }

        assertThat(results).hasSize(4);
        assertThat(results).extracting(result -> result.get("provider"))
                .containsOnly("deepseek", "qwen")
                .hasSize(4);
        assertThat(results).extracting(result -> result.get("learningIntent"))
                .containsExactlyInAnyOrder("EXAMPLE", "SOURCE", "SIMPLIFY", "EXCEPTIONS");

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
    void recordsOnePaidGroundedFollowUpThroughThePlayerVisibleAnswer() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RULEPILOT_REAL_CONTEXT_AGENT_EVAL")));
        String phase = requiredEnvironment("RULEPILOT_ANSWER_CANARY_PHASE");
        assumeTrue(phase.matches("[a-z0-9-]{2,32}"), "canary phase must be a safe bounded label");
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        JsonNode inventory = mapper.readTree(root.resolve(".local/public-corpus/source-preflight.json").toFile());
        Path rulebook = rulebookForTitle(root, inventory, "Root: Learning to Play");
        ProviderConfiguration configured = provider("deepseek");
        Path rawOutput = root.resolve(
                ".local/agent-evaluation/answer-context-canary-" + phase + "-raw.jsonl");
        Path summaryOutput = root.resolve(
                ".local/agent-evaluation/answer-context-canary-" + phase + ".json");
        assumeTrue(!Files.exists(rawOutput) && !Files.exists(summaryOutput),
                "context canary evidence already exists; choose a new phase label instead of overwriting it");

        String question = "那我们只有两个人时，它还算可以等到10分再打吗？请用法官口吻说明为什么，并告诉我不用它怎么赢。";
        PriorTurnScenario prior = new PriorTurnScenario(
                "统治卡什么时候能打？",
                "达到至少10分后，可在日光阶段打出统治卡并改变胜利条件。",
                21);
        BoundaryAnswer run = runBoundaryAnswer(
                rulebook,
                configured,
                question,
                22,
                rawOutput,
                "grounded-follow-up-" + phase,
                prior);
        UnderstoodQuestion understood = question(run.answer().documentVersionId(), question);
        QuestionContext reviewContext = new QuestionContext(
                run.answer().documentVersionId(), prior.question(), null, PlayerLocale.ZH_CN);
        ReviewRisk reviewRisk = AnswerCritiquePolicy.reviewRisk(
                understood, reviewContext, run.modelRequest(), run.answer());
        List<String> rawCriticResponses = new ArrayList<>();
        long criticStarted = System.nanoTime();
        var review = contentCritic(configured, rawCriticResponses, new DirectAuditedInvocations())
                .review(
                        AnswerCritiquePolicy.request(
                                UUID.randomUUID(),
                                understood,
                                reviewContext,
                                run.modelRequest(),
                                run.answer(),
                                run.refined().evidence()),
                        reviewRisk,
                        "agent-evaluation");
        long criticLatencyMs = Duration.ofNanos(System.nanoTime() - criticStarted).toMillis();
        RuleAnswerRateLimiter limiter = mock(RuleAnswerRateLimiter.class);
        when(limiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        AnswerPostPublicationReviewer.Result reviewed = new AnswerPostPublicationReviewer(
                        (request, risk) -> review,
                        new AnswerModelGateway(answerModel(configured, run.audited()), limiter, run.audited()),
                        new AnswerPublicationValidator(new PolicyEvidenceVerifier()))
                .review(
                        UUID.randomUUID(),
                        understood,
                        reviewContext,
                        "agent-evaluation",
                        null,
                        run.modelRequest(),
                        run.preparedDraft(),
                        run.answer(),
                        run.refined().evidence());
        assertThat(reviewed.accepted()).isTrue();
        StructuredRuleAnswer published = reviewed.answer();
        PlayerFacingRuleAnswer player = PlayerFacingAnswerPresenter.present(
                published, question, PlayerLocale.ZH_CN);

        Map<String, Object> summary = new LinkedHashMap<>(boundarySummary(run));
        summary.put("schemaVersion", 1);
        summary.put("generatedAt", Instant.now().toString());
        summary.put("phase", phase);
        summary.put("question", question);
        summary.put("priorTurn", prior);
        summary.put("referenceBinding", run.plan().referenceBinding().name());
        summary.put("reviewRisk", reviewRisk.name());
        summary.put("criticProviderCalls", rawCriticResponses.size());
        summary.put("criticLatencyMs", criticLatencyMs);
        summary.put("totalProviderCalls",
                run.audited().answerPromptSizes.size()
                        + run.audited().rawNativeTurns.size()
                        + rawCriticResponses.size());
        summary.put("criticIssues", review.issues());
        summary.put("sameAgentAnswerRepairs", Math.max(0, run.audited().rawAnswerDrafts.size() - 1));
        summary.put("publishedAnswer", visibleAnswer(published));
        summary.put("playerVisibleAnswer", player);
        summary.put("domainToPlayerCoreExact", published.shortVerdict().equals(player.shortVerdict())
                && published.explanation().equals(player.explanation()));
        summary.put("lastProviderDraftToPublishedCoreExact",
                run.audited().lastAnswerDraft.shortVerdict().equals(published.shortVerdict())
                        && run.audited().lastAnswerDraft.explanation().equals(published.explanation()));
        summary.put("providerRawToPublishedVerdictPreserved",
                run.audited().lastAnswerDraft.shortVerdict().equals(published.shortVerdict()));
        summary.put("providerRawToPublishedExplanationPreserved",
                run.audited().lastAnswerDraft.explanation().equals(published.explanation()));
        summary.put("prosePreserved",
                run.audited().lastAnswerDraft.shortVerdict().equals(published.shortVerdict())
                        && run.audited().lastAnswerDraft.explanation().equals(published.explanation()));
        summary.put("sameVersionOnly", run.refined().evidence().stream()
                .allMatch(hit -> published.documentVersionId().equals(hit.evidence().documentVersionId())));
        Files.writeString(
                summaryOutput,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        Files.writeString(
                rawOutput,
                mapper.writeValueAsString(Map.of(
                                "criticProviderResponses", rawCriticResponses,
                                "criticReview", review,
                                "publishedAfterReview", visibleAnswer(published)))
                        + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        String visible = visibleText(published);
        assertThat(run.plan().referenceBinding())
                .isIn(ReferenceBinding.PREVIOUS_QUESTION, ReferenceBinding.PRIOR_GROUNDED_TURN);
        assertThat(run.plan().boundReferenceQuestion()).isEqualTo(prior.question());
        assertThat(published.status().publishesConclusion()).isTrue();
        assertThat(visible).contains("两人", "统治卡", "30");
        assertThat(published.citations()).anyMatch(citation -> citation.pageFrom() == 22);
        assertThat(published.citations()).anyMatch(citation -> citation.pageFrom() == 2);
        assertThat(run.audited().rawAnswerDrafts).isNotEmpty();
        assertThat(summary)
                .containsEntry("prosePreserved", true)
                .containsEntry("providerRawToPublishedVerdictPreserved", true)
                .containsEntry("providerRawToPublishedExplanationPreserved", true)
                .containsEntry("domainToPlayerCoreExact", true)
                .containsEntry("lastProviderDraftToPublishedCoreExact", true)
                .containsEntry("sameVersionOnly", true);
    }

    private Map<String, Object> runCase(CaseConfiguration case_, Path rawOutput) throws IOException {
        UUID versionId = UUID.nameUUIDFromBytes(case_.caseId().getBytes(StandardCharsets.UTF_8));
        UUID runId = UUID.randomUUID();
        PdfRulebookEvidence corpus = new PdfRulebookEvidence(case_.pdf(), versionId);
        DirectAuditedInvocations audited = new DirectAuditedInvocations();
        SpringAiRuleAnswerModel semanticModel = answerModel(case_.provider(), audited);
        RuleAnswerRateLimiter interpretationLimiter = mock(RuleAnswerRateLimiter.class);
        when(interpretationLimiter.acquireModel(any(String.class), any(), any(String.class))).thenReturn(() -> {});
        AnswerModelGateway modelGateway = new AnswerModelGateway(semanticModel, interpretationLimiter, audited);
        QuestionContext questionContext = new QuestionContext(versionId);
        UnderstoodQuestion understood = question(versionId, case_.question());
        AnswerQuestionPlan plan = modelGateway
                .interpretQuestion(
                        runId,
                        "agent-evaluation",
                        null,
                        new QuestionInterpretationRequest(
                                case_.question(),
                                "",
                                "",
                                "",
                                understood.type(),
                                understood.missingContext(),
                                null,
                                PlayerLocale.EN))
                .flatMap(draft -> new AnswerQuestionInterpretationPolicy()
                        .applyWithPlan(understood, questionContext, draft))
                .map(AnswerQuestionInterpretationPolicy.Interpretation::plan)
                .orElseThrow(() -> new AssertionError(
                        case_.caseId() + " did not return a valid structured question plan; visible responses="
                                + audited.rawAnswerProviderResponses));
        ToolScope scope = new ToolScope("agent-evaluation", versionId, runId, Instant.now().plusSeconds(90));
        AnswerEvidenceAgent agent = answerAgent(case_.provider(), corpus, audited, scope);
        AnswerEvidenceRetriever.Result deterministic = ready(corpus.hit(case_.initialPage()));
        long started = System.nanoTime();

        AnswerEvidenceRetriever.Result refined = agent.refine(
                runId,
                understood,
                questionContext,
                "agent-evaluation",
                null,
                plan,
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
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("caseId", case_.caseId());
        raw.put("provider", case_.provider().provider());
        raw.put("model", case_.provider().model());
        raw.put("question", case_.question());
        raw.put("answerProviderVisibleResponses", List.copyOf(audited.rawAnswerProviderResponses));
        raw.put("questionInterpretations", List.copyOf(audited.rawQuestionInterpretations));
        raw.put("acceptedEvidenceNeeds", plan.evidenceNeeds());
        raw.put("nativeVisibleTurns", List.copyOf(audited.rawNativeTurns));
        raw.put("nativeToolTrace", List.copyOf(audited.trace));
        raw.put("nativeRunReason", audited.lastRunReason);
        raw.put("refinedEvidencePages", refined.evidence().stream()
                .map(hit -> hit.evidence().pageFrom())
                .distinct()
                .toList());
        Files.writeString(
                rawOutput,
                mapper.writeValueAsString(raw) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
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
        return answerModel(provider, null);
    }

    private SpringAiRuleAnswerModel answerModel(
            ProviderConfiguration provider, DirectAuditedInvocations audited) {
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        ChatModel chatModel = audited == null ? delegate : new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                audited.recordAnswerPrompt(prompt);
                var response = delegate.call(prompt);
                String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                audited.recordAnswerProviderResponse(text == null ? "" : text);
                return response;
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return delegate.getDefaultOptions();
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return delegate.getOptions();
            }
        };
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(chatModel);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.ANSWER, "agent-evaluation"))
                .thenReturn(chatModel);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.ANSWER, "agent-evaluation"))
                .thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(provider.model());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.ANSWER, "agent-evaluation"))
                .thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.ANSWER)).thenReturn(false);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.ANSWER, "agent-evaluation"))
                .thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.ANSWER))
                .thenReturn("deepseek".equals(provider.provider()));
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.ANSWER, "agent-evaluation"))
                .thenReturn("deepseek".equals(provider.provider()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            return new SpringAiRuleAnswerModel(
                    configuration, new FakeRuleAnswerModel(), context.getBean(VersionedAgentPrompts.class));
        }
    }

    private com.rulepilot.assistant.GeneratedContentCritic contentCritic(
            ProviderConfiguration provider,
            List<String> rawResponses,
            DirectAuditedInvocations audited) {
        ChatModel delegate = new ChatModelFactory(ObservationRegistry.NOOP, Duration.ofSeconds(120))
                .create(provider.provider(), provider.apiKey(), provider.baseUrl(), provider.model());
        ChatModel recording = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                var response = delegate.call(prompt);
                String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                rawResponses.add(text == null ? "" : text);
                return response;
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return delegate.getDefaultOptions();
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return delegate.getOptions();
            }
        };
        RuntimeModelConfiguration configuration = mock(RuntimeModelConfiguration.class);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(recording);
        when(configuration.modelFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation")).thenReturn(recording);
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.provider());
        when(configuration.providerFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(provider.provider());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(provider.model());
        when(configuration.modelNameFor(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn(provider.model());
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC)).thenReturn(false);
        when(configuration.usesFake(RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation")).thenReturn(false);
        when(configuration.usesDeepSeekNonThinkingGeneration(RuntimeModelConfiguration.Role.CRITIC))
                .thenReturn("deepseek".equals(provider.provider()));
        when(configuration.usesDeepSeekNonThinkingGeneration(
                        RuntimeModelConfiguration.Role.CRITIC, "agent-evaluation"))
                .thenReturn("deepseek".equals(provider.provider()));
        VersionedAgentPrompts prompts;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(VersionedAgentPrompts.class);
            context.refresh();
            prompts = context.getBean(VersionedAgentPrompts.class);
        }
        return new ConditionalGeneratedContentCritic(
                new SpringAiContentCriticModel(configuration, new FakeContentCriticModel(), prompts),
                audited,
                false);
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

    private Path rulebookForTitle(Path root, JsonNode inventory, String title) {
        JsonNode source = java.util.stream.StreamSupport.stream(
                        inventory.path("qualifiedRulebooks").spliterator(), false)
                .filter(candidate -> title.equals(candidate.path("title").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("real rulebook title is not in the qualified corpus"));
        Path pdf = root.resolve(".local/public-corpus/pdfs").resolve(source.path("file").asText());
        assumeTrue(Files.isRegularFile(pdf), "ignored real rulebook is required");
        return pdf;
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

    private record VisualTranscriptionCase(
            String caseId,
            String provider,
            String question,
            String factualSummary,
            PlayerLocale locale,
            String expectedTerm) {}

    private record BoundaryAnswer(
            AnswerQuestionPlan plan,
            ModelRequest modelRequest,
            AnswerEvidenceRetriever.Result refined,
            StructuredRuleAnswer answer,
            ModelDraft preparedDraft,
            ModelDraft providerRawDraft,
            DirectAuditedInvocations audited,
            long latencyMs) {}

    private record ProviderBoundaryRuns(
            ProviderConfiguration provider,
            BoundaryAnswer winCondition,
            BoundaryAnswer sourcedStrategy,
            BoundaryAnswer unsupportedStrategy) {}

    private record DialogueIntentCase(
            String caseId,
            String provider,
            String previousQuestion,
            String followUp,
            PlayerLocale locale,
            LearningIntent expectedIntent,
            String interactionTag) {}

    private record ImaginativeAnswerCase(String caseId, String question, int initialPage) {}

    private record PriorTurnScenario(String question, String groundedVerdict, int citationPage) {}

    private static final class DirectAuditedInvocations implements AuditedAgentInvocations {
        private int modelCalls;
        private int toolCalls;
        private int nativeRuns;
        private String lastRunReason = "NOT_STARTED";
        private Set<String> requestedToolPortfolio = Set.of();
        private Set<String> registeredToolPortfolio = Set.of();
        private final List<String> trace = new ArrayList<>();
        private final List<Map<String, Object>> rawNativeTurns = new ArrayList<>();
        private final List<Map<String, Object>> rawAnswerProviderResponses = new ArrayList<>();
        private final List<Map<String, Object>> answerPromptSizes = new ArrayList<>();
        private final List<Map<String, Object>> rawQuestionInterpretations = new ArrayList<>();
        private final List<Map<String, Object>> rawAnswerDrafts = new ArrayList<>();
        private ModelDraft lastAnswerDraft;

        private void recordAnswerProviderResponse(String text) {
            rawAnswerProviderResponses.add(Map.of(
                    "responseIndex", rawAnswerProviderResponses.size() + 1,
                    "text", text));
        }

        private void recordAnswerPrompt(org.springframework.ai.chat.prompt.Prompt prompt) {
            String contents = prompt == null ? "" : prompt.getContents();
            int systemChars = prompt == null || prompt.getInstructions().isEmpty()
                    ? 0
                    : prompt.getInstructions().getFirst().getText().length();
            answerPromptSizes.add(Map.of(
                    "callIndex", answerPromptSizes.size() + 1,
                    "kind", contents.contains("<validated_subquestions>") ? "COMPOSE_OR_REPAIR" : "INTERPRETATION",
                    "systemChars", systemChars,
                    "totalChars", contents.length()));
        }

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
                Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("operation", operation);
                visible.put("text", turn.text());
                visible.put("toolCalls", turn.toolCalls());
                visible.put("promptTokens", turn.promptTokens());
                visible.put("completionTokens", turn.completionTokens());
                rawNativeTurns.add(Map.copyOf(visible));
            } else if (result instanceof QuestionInterpretationDraft interpretation) {
                recordQuestionInterpretation(operation, interpretation);
            } else if (result instanceof java.util.Optional<?> optional
                    && optional.orElse(null) instanceof QuestionInterpretationDraft interpretation) {
                recordQuestionInterpretation(operation, interpretation);
            } else if (result instanceof ModelDraft draft) {
                lastAnswerDraft = draft;
                Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("operation", operation);
                visible.put("draft", draft);
                rawAnswerDrafts.add(Map.copyOf(visible));
            } else if (result instanceof NativeAgentToolRegistry.ToolExecution tool) {
                trace.add("tool:" + tool.specification().name() + ':' + tool.observation().code()
                        + ":evidence=" + tool.observation().evidenceCount());
            }
            return result;
        }

        private void recordQuestionInterpretation(
                String operation, QuestionInterpretationDraft interpretation) {
                Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("operation", operation);
                visible.put("interpretation", interpretation);
                rawQuestionInterpretations.add(Map.copyOf(visible));
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
