package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.QuestionUnderstanding.PriorCitationReference;
import com.rulepilot.assistant.QuestionUnderstanding.PriorTurnReference;
import com.rulepilot.assistant.AgentExecutionControl;
import com.rulepilot.assistant.AuditedAgentInvocations;
import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Issue;
import com.rulepilot.assistant.GeneratedContentCritic.IssueType;
import com.rulepilot.assistant.ImmediateAuditedAgentInvocations;
import com.rulepilot.assistant.PlayerLocale;
import com.rulepilot.assistant.RuleAnswerModel;
import com.rulepilot.assistant.RuleAnswerModel.CalculationRequest;
import com.rulepilot.assistant.RuleAnswerModel.DecisionBranchRequest;
import com.rulepilot.assistant.RuleAnswerModel.ExceptionClauseRequest;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationDraft;
import com.rulepilot.assistant.RuleAnswerModel.QuestionInterpretationRequest;
import com.rulepilot.assistant.RuleAnswerModel.ReferenceBinding;
import com.rulepilot.assistant.RuleAnswerModel.RetrievalQueryRequest;
import com.rulepilot.assistant.RuleAnswerModel.SituationCheckRequest;
import com.rulepilot.assistant.RuleAnswerModel.TermDefinitionRequest;
import com.rulepilot.assistant.RuleAnswerModel.WalkthroughStepRequest;
import com.rulepilot.assistant.RuleAnswerModel.WorkedExampleRequest;
import com.rulepilot.assistant.RuleAnswerModel.RulePriorityRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTimingRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleTieRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleScopeRequest;
import com.rulepilot.assistant.RuleAnswerModel.RuleConceptComparisonRequest;
import com.rulepilot.assistant.RuleAnswerModelTimeoutException;
import com.rulepilot.assistant.application.RuleAnswerCache.AnswerCacheKey;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.document.RuleDataVersion;
import com.rulepilot.retrieval.HybridRuleSearch;
import com.rulepilot.retrieval.RuleEvidenceLookup;
import com.rulepilot.retrieval.VisualRulebookPageFactSearch;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import com.rulepilot.retrieval.evidence.RuleEvidenceHit;
import com.rulepilot.ruling.ConfirmedRulingLookup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredRuleAnswerServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final DeterministicQuestionUnderstanding understanding = new DeterministicQuestionUnderstanding();

    @Test
    void publishesARecomputedGroundedCalculationSeparateFromRuleEvidence() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "SCORING",
                "Set scoring",
                "Each complete set of 3 resources scores 5 points.",
                8,
                8,
                0.9);
        RuleAnswerModel model = request -> new ModelDraft(
                true,
                null,
                "You score 10 points.",
                "Eight resources contain two complete sets, so you score 10 points and have two resources left over.",
                List.of(source.chunkId()),
                List.of(),
                "HIGH",
                "GROUNDED_APPLICATION",
                List.of(new CalculationRequest("floor(8 / 3) * 5")));
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.9, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "I have 8 resources. How many points do I score?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.answerBasis().name()).isEqualTo("GROUNDED_APPLICATION");
        assertThat(answer.calculations()).singleElement().satisfies(calculation -> {
            assertThat(calculation.expression()).isEqualTo("floor(8 / 3) * 5");
            assertThat(calculation.result()).isEqualTo("10");
        });
        assertThat(answer.citations()).singleElement().satisfies(citation ->
                assertThat(citation.excerpt()).doesNotContain("10"));
    }

    @Test
    void repairsAnInventedCalculationOperandBeforePublishing() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SCORING", "Set scoring",
                "Each complete set of 3 resources scores 5 points.", 8, 8, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        true,
                        null,
                        "You score 10 points.",
                        "The calculation gives 10 points.",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "GROUNDED_APPLICATION",
                        List.of(new CalculationRequest("floor(8 / 4) * 5")));
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("numeric operand"));
                return new ModelDraft(
                        true,
                        null,
                        "Count only complete sets.",
                        "The cited rule scores each complete set of three resources at five points.",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "DIRECT_RULE",
                        List.of());
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.9, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "I have 8 resources. How many points do I score?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.calculations()).isEmpty();
        assertThat(answer.shortVerdict()).doesNotContain("10");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAMissingProceduralWalkthroughAndPublishesSeparatelyCitedSteps() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "PROCEDURE", "Resolution procedure",
                "First pay the cost. Then resolve the effect.", 4, 4, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Pay the cost, then resolve the effect.",
                        "The rule gives these operations in sequence.",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("RULE_ORDER"));
                return new ModelDraft(
                        true, null, "Pay, then resolve.", "Follow the cited two-step procedure.",
                        List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(),
                        List.of(
                                new WalkthroughStepRequest(
                                        "Pay the cost.", "Complete the required payment first.",
                                        "RULE_ORDER", List.of(source.chunkId())),
                                new WalkthroughStepRequest(
                                        "Resolve the effect.", "Apply the effect after payment.",
                                        "RULE_ORDER", List.of(source.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "How do I resolve combat?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.walkthroughSteps()).extracting(step -> step.instruction())
                .containsExactly("Pay the cost.", "Resolve the effect.");
        assertThat(answer.walkthroughSteps()).allSatisfy(step ->
                assertThat(step.citationIds()).containsExactly(source.chunkId()));
        assertThat(revisions).hasValue(1);
    }

    @Test
    void auditsWhyWalkthroughsAsRuleDependencyTracingRatherThanProceduralInstructions() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "RULE", "Dependency",
                "Before entering, pay the required cost. After payment, enter the area.", 5, 5, 0.95);
        RuleAnswerModel model = request -> new ModelDraft(
                true, null,
                "You may enter after paying the required cost.",
                "Payment is the cited prerequisite for entry.",
                List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(),
                List.of(
                        new WalkthroughStepRequest(
                                "Pay the required cost.", "The rule requires payment before entry.",
                                "RULE_ORDER", List.of(source.chunkId())),
                        new WalkthroughStepRequest(
                                "Enter the area.", "Entry follows after the required payment.",
                                "RULE_ORDER", List.of(source.chunkId()))));
        List<String> operations = new java.util.ArrayList<>();
        AuditedAgentInvocations invocations = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    java.util.function.Supplier<T> invocation,
                    java.util.function.ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.95, 1, null, false)),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                invocations,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        StructuredRuleAnswer answer = service.answer(
                "Why may I enter after paying?",
                new QuestionContext(versionId, null, LearningIntent.WHY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.walkthroughSteps()).hasSize(2);
        assertThat(operations).contains("traceRuleDependencies").doesNotContain("buildRuleWalkthrough");
    }

    @Test
    void auditsAnAllegedConflictWithoutInventingARulePriorityWinner() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SCOPE", "Entry timing",
                "The archive entry rule applies only during Dawn. "
                        + "The vault entry rule applies only during Dusk.",
                7, 7, 0.95);
        RuleAnswerModel model = request -> new ModelDraft(
                true, null,
                "The rules do not conflict: the archive rule applies during Dawn, while the vault rule applies during Dusk.",
                "They govern the same kind of action under different cited timing conditions, so neither overrides the other.",
                List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new RuleConceptComparisonRequest(
                        "archive entry rule",
                        "It applies only during Dawn.",
                        "vault entry rule",
                        "It applies only during Dusk.",
                        "Both rules govern entry into a named area.",
                        "Their timing conditions are different.",
                        "Use the archive entry rule only during Dawn and the vault entry rule only during Dusk; neither overrides the other.",
                        "RULE_SCOPE",
                        List.of(source.chunkId()))));
        List<String> operations = new java.util.ArrayList<>();
        AuditedAgentInvocations invocations = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    java.util.function.Supplier<T> invocation,
                    java.util.function.ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.95, 1, null, false)),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                invocations,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        StructuredRuleAnswer answer = service.answer(
                "Do the archive entry rule and vault entry rule conflict?",
                new QuestionContext(versionId, null, LearningIntent.VERIFY, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.priorityResolutions()).isEmpty();
        assertThat(answer.conceptComparisons()).singleElement().satisfies(comparison -> {
            assertThat(comparison.basis().name()).isEqualTo("RULE_SCOPE");
            assertThat(comparison.practicalBoundary()).contains("Dawn", "Dusk");
        });
        assertThat(operations).contains("checkRuleConflicts").doesNotContain("resolveRulePriority");
    }

    @Test
    void auditsAPlayerRequestForTheDirectRulebookSource() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Beacon",
                "When the beacon is lit, each player may draw one card.", 9, 9, 0.97);
        RuleAnswerModel model = request -> new ModelDraft(
                true, null,
                "Players may draw one card each when the beacon is lit.",
                "The cited clause makes lighting the beacon the trigger and gives the draw permission to each player.",
                List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        List<String> operations = new java.util.ArrayList<>();
        AuditedAgentInvocations invocations = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    java.util.function.Supplier<T> invocation,
                    java.util.function.ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.97, 1, null, false)),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                invocations,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        StructuredRuleAnswer answer = service.answer(
                "Where does the rulebook say when players draw from the beacon?",
                new QuestionContext(versionId, null, LearningIntent.SOURCE, PlayerLocale.EN));

        assertThat(answer.status()).as("operations: %s, verdict: %s", operations, answer.shortVerdict())
                .isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.pageFrom()).isEqualTo(9);
            assertThat(citation.excerpt()).isEqualTo(source.excerpt());
        });
        assertThat(operations).contains("showRuleEvidence");
    }

    @Test
    void auditsAnExplicitCanQuestionAsAPermissionRuling() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Beacon",
                "Each player may draw one card after lighting the beacon.", 9, 9, 0.97);
        RuleAnswerModel model = request -> new ModelDraft(
                true, null,
                "Yes, each player may draw one card after lighting the beacon.",
                "Lighting the beacon satisfies the cited condition for that permission.",
                List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        List<String> operations = new java.util.ArrayList<>();
        AuditedAgentInvocations invocations = new AuditedAgentInvocations() {
            @Override
            public <T> T invoke(
                    UUID runId,
                    AgentExecutionControl.ActivityType type,
                    String operation,
                    int estimatedInputTokens,
                    String successSummary,
                    java.util.function.Supplier<T> invocation,
                    java.util.function.ToIntFunction<T> outputTokenEstimator) {
                operations.add(operation);
                return invocation.get();
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.97, 1, null, false)),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                invocations,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        StructuredRuleAnswer answer = service.answer(
                "Can players draw after lighting the beacon?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).startsWith("Yes");
        assertThat(operations).contains("checkRulePermission");
    }

    @Test
    void repairsAMissingBranchComparisonAndPublishesSeparatelyCitedOutcomes() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "TIES", "Tie rewards",
                "Players tied for first each receive the second reward. Players tied for second each receive the third reward.",
                12, 12, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Tie rewards depend on the tied place.",
                        "The rule states a different reward for each tie.",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("EXPLICIT_RULE"));
                return new ModelDraft(
                        true, null, "Compare the tied place.", "Each condition keeps its cited outcome.",
                        List.of(source.chunkId()), List.of(), "HIGH", "DIRECT_RULE", List.of(), List.of(), List.of(),
                        List.of(
                                new DecisionBranchRequest(
                                        "Players tie for first place.", "Each receives the second reward.",
                                        "EXPLICIT_RULE", List.of(source.chunkId())),
                                new DecisionBranchRequest(
                                        "Players tie for second place.", "Each receives the third reward.",
                                        "EXPLICIT_RULE", List.of(source.chunkId()))),
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new RuleTieRequest(
                                "Players tie for first or second place.",
                                List.of(
                                        "A tie for first receives the second reward.",
                                        "A tie for second receives the third reward."),
                                "Each tied rank receives the lower reward stated for that rank.",
                                "RANK_REWARD_SHIFT",
                                List.of(source.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "What happens in each case when players tie for first or second?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.decisionBranches()).extracting(branch -> branch.outcome())
                .containsExactly("Each receives the second reward.", "Each receives the third reward.");
        assertThat(answer.decisionBranches()).allSatisfy(branch ->
                assertThat(branch.citationIds()).containsExactly(source.chunkId()));
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAnUnstructuredExceptionAnswerAndPublishesEachConditionWithItsOwnCitation() {
        RuleEvidenceHit supply = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "CRAFTING", "Supply limit",
                "If the matching item is not in the supply, the card cannot be crafted.", 7, 7, 0.95);
        RuleEvidenceHit duplicate = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "CRAFTING", "Persistent limit",
                "A persistent effect cannot be crafted if the player already has one with the same name.", 8, 8, 0.94);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Two limits apply.",
                        "The supply and duplicate-name restrictions both matter.",
                        List.of(supply.chunkId(), duplicate.chunkId()),
                        List.of("Do not ignore the limits."),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("condition", "effect", "citationIds"));
                return new ModelDraft(
                        true, null, "Two cited limits apply.", "Check the matching item and existing persistent effect.",
                        List.of(supply.chunkId(), duplicate.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(),
                        List.of(
                                new ExceptionClauseRequest(
                                        "The matching item is unavailable in the supply.",
                                        "The card cannot be crafted.",
                                        List.of(supply.chunkId())),
                                new ExceptionClauseRequest(
                                        "The player already has a persistent effect with the same name.",
                                        "Another copy cannot be crafted.",
                                        List.of(duplicate.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(
                        new HybridEvidenceHit(supply, 0.95, 1, null, false),
                        new HybridEvidenceHit(duplicate, 0.94, 2, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "What exceptions or limits apply when crafting?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.exceptions()).isEmpty();
        assertThat(answer.exceptionClauses()).extracting(clause -> clause.effect())
                .containsExactly("The card cannot be crafted.", "Another copy cannot be crafted.");
        assertThat(answer.exceptionClauses().get(0).citationIds()).containsExactly(supply.chunkId());
        assertThat(answer.exceptionClauses().get(1).citationIds()).containsExactly(duplicate.chunkId());
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAnUnstructuredDefineAnswerAndPublishesEachTermWithItsOwnCitation() {
        RuleEvidenceHit control = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "TERMS", "Control",
                "You control an area when you have more pieces there than every opponent. A tie is not control.",
                9, 9, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Control means having more pieces.", "A tie does not count.",
                        List.of(control.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("term", "definition", "citationIds"));
                return new ModelDraft(
                        true, null, "Control requires a strict majority.", "Use the cited definition and tie boundary.",
                        List.of(control.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new TermDefinitionRequest(
                                "Control", "Having more pieces in the area than every opponent.",
                                "A tie does not grant control.", List.of(control.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(control, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "What does control mean?",
                new QuestionContext(versionId, null, LearningIntent.DEFINE, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.termDefinitions()).singleElement().satisfies(definition -> {
            assertThat(definition.term()).isEqualTo("Control");
            assertThat(definition.boundary()).isEqualTo("A tie does not grant control.");
            assertThat(definition.citationIds()).containsExactly(control.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAProseOnlyExampleAndPublishesItsSetupActionOutcomeWithOwnCitation() {
        RuleEvidenceHit example = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "EXAMPLE", "Negative modifier example",
                "A card with base value 1 and a -4 modifier has final value -3.",
                11, 11, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Apply the modifier to the base value.", "For example, 1 plus -4 becomes -3.",
                        List.of(example.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item)
                        .contains("setup", "action", "outcome", "basis", "citationIds"));
                return new ModelDraft(
                        true, null, "Apply the modifier to the base value.",
                        "The cited example shows the arithmetic from starting value to result.",
                        List.of(example.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new WorkedExampleRequest(
                                "A card has base value 1 and a -4 modifier.",
                                "Apply the -4 modifier to the base value.",
                                "The final value is -3.",
                                "RULEBOOK_EXAMPLE",
                                List.of(example.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(example, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "Give me the rulebook's example of a negative modifier.",
                new QuestionContext(versionId, null, LearningIntent.EXAMPLE, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.workedExamples()).singleElement().satisfies(worked -> {
            assertThat(worked.setup()).contains("base value 1", "-4 modifier");
            assertThat(worked.outcome()).contains("-3");
            assertThat(worked.basis().name()).isEqualTo("RULEBOOK_EXAMPLE");
            assertThat(worked.citationIds()).containsExactly(example.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAProseOnlyPriorityRulingAndPublishesTheExplicitRelationship() {
        RuleEvidenceHit priority = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "PRIORITY", "Fundamental rules",
                "Effects of cards override rules. If one effect makes something possible and another makes it impossible, the impossible effect has priority.",
                24, 24, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "The impossible effect has priority.",
                        "The rulebook explicitly resolves the conflict.",
                        List.of(priority.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item)
                        .contains("baseRule", "competingRule", "resolution", "citationIds"));
                return new ModelDraft(
                        true, null, "The impossible effect has priority.",
                        "The cited priority rule resolves this exact possible-versus-impossible conflict.",
                        List.of(priority.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new RulePriorityRequest(
                                "One effect makes the action possible.",
                                "Another effect makes the action impossible.",
                                "The impossible effect has priority, so the action remains impossible.",
                                "IMPOSSIBILITY_PRIORITY",
                                List.of(priority.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(priority, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "If one effect allows an action and another forbids it, which rule takes priority?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.priorityResolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.baseRule()).contains("possible");
            assertThat(resolution.competingRule()).contains("impossible");
            assertThat(resolution.resolution()).contains("remains impossible");
            assertThat(resolution.basis().name()).isEqualTo("IMPOSSIBILITY_PRIORITY");
            assertThat(resolution.citationIds()).containsExactly(priority.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAProseOnlyTimingRulingAndPublishesTheExplicitOrderSource() {
        RuleEvidenceHit timing = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "TIMING", "Simultaneous timing",
                "If two things happen at the same time, the player taking their turn chooses the order.",
                22, 22, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "The player taking the turn chooses the order.",
                        "The simultaneous-timing rule assigns the choice to the current player.",
                        List.of(timing.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item)
                        .contains("timingContext", "resolutionOrder", "orderSource", "citationIds"));
                return new ModelDraft(
                        true, null, "The player taking the turn chooses the order.",
                        "The cited rule directly assigns the ordering choice to that player.",
                        List.of(timing.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new RuleTimingRequest(
                                "Two things happen at the same time during a player's turn.",
                                "Resolve them in the order selected by that player.",
                                "The player taking the current turn.",
                                "CURRENT_PLAYER_CHOOSES",
                                List.of(timing.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(timing, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "If two things happen at the same time, who chooses the order?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.timingResolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.timingContext()).contains("same time");
            assertThat(resolution.resolutionOrder()).contains("selected by that player");
            assertThat(resolution.orderSource()).contains("player taking the current turn");
            assertThat(resolution.basis().name()).isEqualTo("CURRENT_PLAYER_CHOOSES");
            assertThat(resolution.citationIds()).containsExactly(timing.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAProseOnlyTieRulingAndPublishesTheCompleteTieBreakLadder() {
        RuleEvidenceHit ties = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SCORING", "Ties",
                "If tied, compare treasure difficulty, then hero cost, then gold. If still tied, share the win.",
                12, 12, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Compare the listed values in order.",
                        "The scoring rule gives several tie-breakers and then a shared win.",
                        List.of(ties.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item)
                        .contains("resolutionSteps", "finalOutcome", "citationIds"));
                return new ModelDraft(
                        true, null, "Compare difficulty, hero cost, then gold; share the win if still tied.",
                        "Apply each cited tie-breaker without skipping the terminal shared result.",
                        List.of(ties.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(),
                        List.of(new RuleTieRequest(
                                "Players are tied for the most treasure.",
                                List.of(
                                        "Compare total treasure difficulty.",
                                        "If still tied, compare total hero cost.",
                                        "If still tied, compare gold."),
                                "If still tied after gold, the tied players share the win.",
                                "ORDERED_TIEBREAKERS",
                                List.of(ties.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(ties, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "If players tie for the most treasure, how is the tie broken?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.tieResolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.resolutionSteps()).containsExactly(
                    "Compare total treasure difficulty.",
                    "If still tied, compare total hero cost.",
                    "If still tied, compare gold.");
            assertThat(resolution.finalOutcome()).contains("share the win");
            assertThat(resolution.basis().name()).isEqualTo("ORDERED_TIEBREAKERS");
            assertThat(resolution.citationIds()).containsExactly(ties.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAProseOnlyScopeRulingAndPublishesTheCitedApplicabilityMatch() {
        RuleEvidenceHit scope = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Two-player games",
                "When playing with two players, do not use the dominance cards.",
                22, 22, 0.95);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "No. Do not use dominance cards.",
                        "The two-player setup restriction applies.",
                        List.of(scope.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item)
                        .contains("governingCondition", "currentSituation", "matchStatus", "citationIds"));
                return new ModelDraft(
                        true, null, "No. Do not use dominance cards.",
                        "The stated two-player setup matches the cited component restriction.",
                        List.of(scope.chunkId()), List.of(), "HIGH", "GROUNDED_APPLICATION",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of(new RuleScopeRequest(
                                "Dominance cards in a two-player game.",
                                "When playing with two players, do not use the dominance cards.",
                                "We are playing a two-player game.",
                                "MATCHES_SCOPE",
                                "Do not use dominance cards.",
                                "PLAYER_COUNT",
                                List.of(scope.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(scope, 0.95, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "We are playing a two-player game. Can we use dominance cards?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.scopeResolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.governingCondition()).contains("two players");
            assertThat(resolution.currentSituation()).contains("two-player");
            assertThat(resolution.matchStatus().name()).isEqualTo("MATCHES_SCOPE");
            assertThat(resolution.effect()).contains("Do not use");
            assertThat(resolution.basis().name()).isEqualTo("PLAYER_COUNT");
            assertThat(resolution.citationIds()).containsExactly(scope.chunkId());
        });
        assertThat(revisions).hasValue(1);
    }

    @Test
    void publishesOnlyAfterTheEvidenceAgentRefinementReachesTheProductOrchestrator() {
        RuleEvidenceHit deterministic = evidence("ACTIONS");
        RuleEvidenceHit refined = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "PAYMENT", "Payment timing",
                "Pay the cost before placing the piece.", 12, 12, 0.95);
        AtomicInteger refinementCalls = new AtomicInteger();
        AnswerEvidenceRefiner refiner = (runId, question, context, username, gameSessionId, result) -> {
            refinementCalls.incrementAndGet();
            assertThat(result.state()).isEqualTo(AnswerEvidenceRetriever.State.READY);
            assertThat(result.evidence()).extracting(hit -> hit.evidence().chunkId())
                    .contains(deterministic.chunkId());
            return new AnswerEvidenceRetriever.Result(
                    List.of(new HybridEvidenceHit(refined, 0.95, 1, null, false)),
                    AnswerEvidenceRetriever.State.READY);
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(input -> input.chunkId())
                    .containsExactly(refined.chunkId());
            return new ModelDraft(
                    "Pay before placement.",
                    "The payment is resolved before the piece is placed.",
                    List.of(refined.chunkId()),
                    List.of(),
                    "HIGH");
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(deterministic, 0.8, 1, null, false)),
                VisualRulebookPageFactSearch.empty(),
                (documentVersionId, chunkIds) -> List.of(),
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                refiner);

        StructuredRuleAnswer answer = service.answer(
                "When do I pay and place the piece?", new QuestionContext(versionId));

        assertThat(refinementCalls).hasValue(1);
        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(refined.chunkId());
            assertThat(citation.pageFrom()).isEqualTo(12);
        });
    }

    @Test
    void returnsOnlyValidatedCitationsFromCurrentVersion() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, 1, false)),
                request -> new ModelDraft(
                        "Coins score one point.", "Each coin contributes one point.",
                        List.of(source.chunkId()), List.of("Only count remaining coins."), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(source.chunkId());
            assertThat(citation.documentVersionId()).isEqualTo(versionId);
            assertThat(citation.pageFrom()).isEqualTo(8);
        });
        assertThat(answer.official()).isFalse();
    }

    @Test
    void publishesAConditionalApplicationAfterTheModelReconsidersRelevantRulePremises() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Action timing",
                "After completing the listed prerequisite, the player may resolve the action.", 6, 6, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "The result is not copied verbatim", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("jointly determine the result"));
                return new ModelDraft(
                        true,
                        null,
                        "若你已完成该前置条件，就可以结算这个行动。",
                        "规则要求先完成前置条件；按你描述的情况该条件已经满足，因此可以结算行动。",
                        List.of(source.chunkId()),
                        List.of(),
                        "MEDIUM",
                        "GROUNDED_APPLICATION",
                        List.of(),
                        List.of(new SituationCheckRequest(
                                "The listed prerequisite is complete.",
                                "CONFIRMED",
                                "我已经完成前置条件",
                                List.of(source.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.9, 1, null, false)),
                model);

        var answer = service.answer(
                "我已经完成前置条件，现在可以结算这个行动吗？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.answerBasis().name()).isEqualTo("GROUNDED_APPLICATION");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void removesShortInternalEvidenceIdentifiersFromPlayerFacingText() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "可以执行该行动[322c770b]。",
                        "满足规则所列条件后即可执行。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("PLAYER_FACING_OUTPUT"));
                return previousDraft;
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)),
                model);

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).doesNotContain("322c770b");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void answersImageOnlyRulesFromSearchedVisualPageFactsInsteadOfRandomPlaceholderPages() {
        RuleEvidenceHit randomPlaceholder = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "GENERAL",
                "Visual rulebook page 12",
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.",
                12,
                12,
                0.02);
        UUID pageSixChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSixSource = new RuleEvidenceHit(
                pageSixChunkId,
                versionId,
                "GENERAL",
                "Visual rulebook page 6",
                randomPlaceholder.excerpt(),
                6,
                6,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                new VisualRulebookPageFactSearch.PageFactMatch(
                        6,
                        "Overpopulation, Wildlife Token, Nature Token",
                        "4 个相同标记时自动清除且同一回合可重复触发；3 个相同标记时当前玩家可以选择清除，且每回合只能这样做一次。",
                        List.of("Overpopulation", "Wildlife Token"),
                        0.9));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).containsExactly(6);
                return List.of(pageSixSource);
            }
        };
        AtomicReference<RuleAnswerModel.ModelRequest> captured = new AtomicReference<>();
        RuleAnswerModel model = request -> {
            captured.set(request);
            return new ModelDraft(
                    "三个相同标记时可以选择清除。",
                    "当前玩家每回合只能执行一次这种三标记清除；四个相同标记则自动清除，并可能在同一回合再次触发。",
                    List.of(pageSixChunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(randomPlaceholder, 0.02, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "Is clearing three matching wildlife tokens optional?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(captured.get().evidence()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo(pageSixChunkId);
            assertThat(source.excerpt()).contains("每回合只能这样做一次");
            assertThat(source.pageFrom()).isEqualTo(6);
        });
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(pageSixChunkId);
            assertThat(citation.pageFrom()).isEqualTo(6);
        });
    }

    @Test
    void retriesAVisualFactBackedRuleWhenTheQuestionAlreadyStatesItsCondition() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(
                        10,
                        "Draw Zone, Discard Zone",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        95));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                assertThat(pageNumbers).containsExactly(10);
                return List.of(pageSource);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(request.evidence()).singleElement().satisfies(source -> assertThat(source.excerpt())
                        .contains("若抽骰区没有骰子", "弃骰区的所有骰子移回抽骰区"));
                return new ModelDraft(false, "Current state is incomplete.", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback)
                        .singleElement()
                        .asString()
                        .contains("condition written into a player's question", "bounded grounded application")
                        .doesNotContain("replenishment", "DIRECT_REPLENISHMENT_PROCEDURE");
                return new ModelDraft(
                        "把弃骰区全部移回抽骰区，再继续抽骰。",
                        "抽骰区为空时，先回收弃骰区的全部骰子到抽骰区；随后继续本次抽骰流程。",
                        List.of(pageChunkId),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("弃骰区");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(10);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void doesNotSynthesizeADeterministicAnswerAfterTwoModelAbstentions() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "Visual rulebook page evidence is available.",
                10,
                10,
                1.0);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(
                        10,
                        "Draw Zone, Discard Zone",
                        "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                        95));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(pageSource);
            }
        };
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "Unable to compose.", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return new ModelDraft(false, "Still unable to compose.", null, null, List.of(), List.of(), "LOW");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(), visualFacts, pageLookup, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).contains(pageChunkId);
    }

    @Test
    void retrievesConditionalProcedureEvidenceWithoutPredictingTheRulebookVocabulary() {
        UUID pageChunkId = UUID.randomUUID();
        RuleEvidenceHit pageSource = new RuleEvidenceHit(
                pageChunkId,
                versionId,
                "ROUND_STRUCTURE",
                "Draw phase",
                "若抽骰区没有骰子，将弃骰区的所有骰子移回抽骰区，再继续抽骰。",
                10,
                10,
                1.0);
        List<String> retrievalQueries = new java.util.ArrayList<>();
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId).contains(pageChunkId);
            return new ModelDraft(
                    "抽骰区为空时，回收弃骰区后继续抽骰。",
                    "将弃骰区的全部骰子移回抽骰区，再继续本次抽骰。",
                    List.of(pageChunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService((documentVersionId, query, options) -> {
            retrievalQueries.add(query);
            return query.contains("condition procedure")
                    ? List.of(new HybridEvidenceHit(pageSource, 0.9, 1, null, false))
                    : List.of();
        }, model);

        var answer = service.answer(
                "抽骰区的骰子不够我本轮要抽的数量时，应该怎么办？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(retrievalQueries).anyMatch(query -> query.contains("condition procedure")
                && query.contains("条件")
                && query.contains("流程"));
        assertThat(retrievalQueries).noneMatch(query -> query.contains("耗尽") || query.contains("回收"));
    }

    @Test
    void enrichesExtractedTextWithSamePageVisualFactsWhenInlineIconsAreMissing() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit textSource = new RuleEvidenceHit(
                chunkId,
                versionId,
                "SPECIAL_RULE",
                "Wager",
                "Use the wager only if you have at least 2  . Place 2  on it.",
                14,
                14,
                0.8);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(14, "victory point token", "The missing icon is a victory point token.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(textSource);
            }
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).singleElement().satisfies(source -> assertThat(source.excerpt())
                    .contains("at least 2", "victory point token"));
            return new ModelDraft(
                    "支付2个胜利点。",
                    "把2个胜利点放在赌注卡上。",
                    List.of(chunkId),
                    List.of(),
                    "HIGH");
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(textSource, 0.8, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "What token pays for the wager?",
                new QuestionContext(versionId));

        assertThat(answer.shortVerdict()).contains("胜利点");
    }

    @Test
    void repairsAnUnresolvedCrossPageIconIdentityBeforePublishingTheAnswer() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "SETUP",
                "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden behind the screen.",
                3,
                3,
                0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "SPECIAL_RULE",
                "Wager",
                "To make the wager, place 2  on the card. If you win, keep the 2  and gain 2 additional  .",
                9,
                9,
                0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The red icon labels score token; the green icon labels energy token.", 80),
                visualFact(9, "Wager; 2 🔴", "Place 2 🔴 on the wager; the icon is likely an energy token.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "下注支付2个能量令牌（🔴）。",
                        "获胜后保留2个🔴并额外获得2个🔴。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("VISUAL_IDENTITY", "worked arithmetic", "set answerable to false");
                return new ModelDraft(
                        "下注放置2个得分令牌（score token）。",
                        "获胜时保留已经放置的2个得分令牌，再获得2个得分令牌；净增加2个。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌，获胜后怎么结算？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("得分令牌").doesNotContain("🔴", "能量令牌");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void refusesAnAnswerWhenVisualIdentityRepairStillUsesAnUnresolvedIcon() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Payment", "Pay 2  to activate.", 5, 5, 0.8);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) ->
                List.of(visualFact(5, "2 🔴", "The payment shows 2 🔴; the resource name is uncertain.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(source);
            }
        };
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return unresolvedDraft(source.chunkId());
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                return unresolvedDraft(source.chunkId());
            }

            private ModelDraft unresolvedDraft(UUID citationId) {
                return new ModelDraft(
                        "支付2个🔴。", "现有页面只显示🔴图标。", List.of(citationId), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(source, 0.8, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "激活时支付哪一种资源？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("无法从现有证据中可靠确定");
        assertThat(answer.citations()).isNotEmpty();
    }

    @Test
    void doesNotTreatAProceduralRewardQuestionAsAnUnresolvedIconQuestion() {
        RuleEvidenceHit turnRule = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ROUND_STRUCTURE",
                "Dice resolution",
                "The active player rolls both dice and resolves the matching colony rewards. Each other player "
                        + "may resolve the passive reward for the same die value.",
                11,
                11,
                0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(11, "dice icons", "The matching reward rows contain colored icon glyphs.", 0.9));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(turnRule);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "可以；主动玩家结算主动奖励，其他玩家可结算同点数的被动奖励。",
                        "主动玩家先掷两颗骰子并处理对应殖民地奖励；其他玩家针对同一个骰子点数，处理自己卡牌上的被动奖励。",
                        List.of(turnRule.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return new ModelDraft(false, "This answer does not need icon reconciliation", null, null, List.of(), List.of(), "LOW");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(turnRule, 0.9, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "主动玩家掷出的骰子落在殖民地卡上时，其他玩家是否也能获得奖励？双方各要怎样处理？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("主动玩家", "被动奖励");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(11);
        assertThat(revisions).hasValue(0);
    }

    @Test
    void removesAnUnaskedRepeatabilityRestrictionThatItsCitationDoesNotSupport() {
        RuleEvidenceHit rewardRule = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Reward timing",
                "After rolling, each player chooses a reward that matches their result.",
                8,
                8,
                0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "领取匹配结果的奖励。",
                        "按当前结果领取对应奖励；每个奖励最多只能领取一次，以避免无限循环。",
                        List.of(rewardRule.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("UNASKED_REPEATABILITY"));
                return new ModelDraft(
                        "领取匹配结果的奖励。",
                        "按当前结果领取对应奖励。",
                        List.of(rewardRule.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(rewardRule, 0.9, 1, null, false)),
                model);

        var answer = service.answer(
                "掷骰后如何领取奖励？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).doesNotContain("一次", "无限循环");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void usesAnExplicitCrossPageIconMappingWithoutAStochasticSecondModelPass() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Place 2  on the wager. If you win, keep the 2  and gain 2 additional  .", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The reference page labels both components.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "下注放置2个得分令牌（score token）。",
                        "获胜后保留下注，并额外获得2个得分令牌。",
                        List.of(wager.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("得分令牌", "score token");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(0);
    }

    @Test
    void ignoresAnUncitedSupplementaryIconMappingForAnUnrelatedRoundQuestion() {
        RuleEvidenceHit round = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ROUND_END", "Going out",
                "The first player to empty their hand takes first place. Others continue until one player remains.",
                4, 4, 0.9);
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager", "Place 2 on the wager.", 9, 9, 0.7);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "不会立刻结束，其他玩家继续决定后续名次。",
                        "你出完手牌后取得第一名并退出本轮，其余玩家继续。",
                        List.of(round.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(round, 0.9, 1, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "我第一个出完手牌后，其他玩家继续吗？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).doesNotContain("score token");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(4);
        assertThat(revisions).hasValue(0);
    }

    @Test
    void answersAnActorTransitionFromCurrentDocumentEvidence() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ROUND_STRUCTURE", "Next eligible player",
                "The trick winner leads next. If that winner is out of cards, the next player to the left starts instead.",
                8, 8, 0.9);
        RuleAnswerModel model = request -> new ModelDraft(
                "你出完所有手牌后，由你左手边的下一位玩家领出下一墩。",
                "你退出本轮；下一墩不由你领出，改由左手边下一位仍在本轮中的玩家开始。",
                List.of(source.chunkId()), List.of(), "HIGH");
        var service = answerService(
                (documentVersionId, query, options) -> List.of(new HybridEvidenceHit(source, 0.9, 1, null, false)),
                model);

        var answer = service.answer(
                "我出完手牌后，下一墩由谁领出？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("左手边的下一位玩家");
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
    }

    @Test
    void repairsAResourceIconThatTheDraftAlsoMisstatesAsAHandSizeRequirement() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token in public and 2 energy tokens hidden.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Use the wager only if you have at least 2  . Place 2  on it; it is not used as a card this round.",
                9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token; energy token", "The reference page labels both components.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "放置2个得分令牌（score token）。",
                        "至少需要2张基础牌才能发动；图标对应基础牌数量。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of("手牌不足2张时不能发动。"),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("RESOURCE_CARD_CONFLATION", "fewer cards", "named token");
                return new ModelDraft(
                        "放置2个得分令牌（score token）。",
                        "至少拥有2个得分令牌时才能发动；放置后，该牌本轮不再作为手牌使用。",
                        List.of(wager.chunkId(), setup.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "发动时支付哪一种资源？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("2个得分令牌").doesNotContain("2张基础牌", "手牌不足");
        assertThat(revisions).hasValue(1);
    }

    @Test
    void allowsAnExplicitDenialOfAHandSizeRequirementAfterVisualReconciliation() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token and 2 energy tokens.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Use the wager only if you have at least 2 . Place 2 on it.", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "需要2个得分令牌（score token），不需要至少2张手牌。",
                        "发动前检查并放置得分令牌；这张牌本轮不参与出牌，所以使用后你的手牌会更少。",
                        List.of(wager.chunkId(), setup.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "需要至少2张手牌才能发动吗？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("不需要至少2张手牌", "score token");
        assertThat(answer.explanation()).contains("使用后你的手牌会更少");
        assertThat(revisions).hasValue(0);
    }

    @Test
    void replacesAnImprovisedGlyphWithTheResolvedPrintedComponentName() {
        RuleEvidenceHit setup = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Give each player 1 score token.", 3, 3, 0.8);
        RuleEvidenceHit wager = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SPECIAL_RULE", "Wager",
                "Place 2 on the wager.", 9, 9, 0.9);
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) -> List.of(
                visualFact(3, "score token", "The component is labeled 'score token'.", 80),
                visualFact(9, "Wager; score token", "The operational icon is visually identical to the one labeled 'score token' on page 3 and is used as that component name.", 90));
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return List.of(setup, wager);
            }
        };
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "放置2个得分令牌（score token，🔴）。",
                        "获胜后保留2个🔴。",
                        List.of(wager.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anySatisfy(item -> assertThat(item).contains("MAPPED_COMPONENT_GLYPH"));
                return previousDraft;
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> List.of(
                        new HybridEvidenceHit(wager, 0.9, 1, null, false),
                        new HybridEvidenceHit(setup, 0.8, 2, null, false)),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "下注支付哪一种令牌？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("score token").doesNotContain("🔴");
        assertThat(answer.explanation()).contains("score token").doesNotContain("🔴");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(9, 3);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void putsSpecificVisualInstructionsBeforeGenericVisualIntentAnchors() {
        String placeholder =
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
        RuleEvidenceHit components = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "COMPONENTS", "Visual rulebook page 3", placeholder, 3, 3, 0.02);
        RuleEvidenceHit placement = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Visual rulebook page 7", placeholder, 7, 7, 0.02);
        RuleEvidenceHit overview = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Visual rulebook page 8", placeholder, 8, 8, 0.02);
        Map<Integer, RuleEvidenceHit> pages = Map.of(3, components, 7, placement, 8, overview);
        AtomicInteger retrievalCalls = new AtomicInteger();
        HybridRuleSearch hybridSearch = (documentVersionId, query, options) -> switch (retrievalCalls.getAndIncrement()) {
            case 0 -> List.of(new HybridEvidenceHit(components, 0.04, 1, null, false));
            case 1 -> List.of(new HybridEvidenceHit(overview, 0.04, 1, null, false));
            default -> List.of(new HybridEvidenceHit(components, 0.03, 1, null, false));
        };
        AtomicInteger visualCalls = new AtomicInteger();
        VisualRulebookPageFactSearch visualFacts = (documentVersionId, query, limit) ->
                switch (visualCalls.getAndIncrement()) {
                    case 0 -> List.of(
                            visualFact(3, "Wildlife Tokens", "The game contains wildlife tokens.", 20),
                            visualFact(7, "Place the Tile and Token", "Place the token on a legal tile.", 60));
                    case 1 -> List.of(
                            visualFact(8, "Keystone Tile", "A matching token on a Keystone grants a Nature Token.", 50),
                            visualFact(7, "Place the Tile and Token", "Place the token on a legal tile.", 60));
                    default -> List.of(
                            visualFact(3, "Wildlife Tokens", "The game contains wildlife tokens.", 20),
                            visualFact(8, "Keystone Tile", "A matching token on a Keystone grants a Nature Token.", 50));
                };
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.stream().map(pages::get).toList();
            }
        };
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::pageFrom)
                    .startsWith(7)
                    .contains(8);
            return new ModelDraft(
                    "放在显示对应动物图标的空板块上。",
                    "可以放在新板块或环境中其他合法板块；放在关键石板块上获得一个自然标记。",
                    List.of(placement.chunkId(), overview.chunkId()),
                    List.of("每个板块最多放一个动物标记。"),
                    "HIGH");
        };
        var service = answerService(hybridSearch, visualFacts, pageLookup, model);

        var answer = service.answer(
                "Where can I place a Wildlife Token, and what do I gain after placing one on a Keystone Tile?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(7, 8);
    }

    @Test
    void preservesDirectQuestionVisualFactsWhenRewriteQueriesReturnHigherScoredGenericPages() {
        String placeholder =
                "This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.";
        RuleEvidenceHit pageTwo = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 2", placeholder, 2, 2, 0.01);
        RuleEvidenceHit pageThree = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 3", placeholder, 3, 3, 0.01);
        RuleEvidenceHit pageFour = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 4", placeholder, 4, 4, 0.01);
        RuleEvidenceHit pageFive = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 5", placeholder, 5, 5, 0.01);
        RuleEvidenceHit pageSeven = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 7", placeholder, 7, 7, 0.01);
        RuleEvidenceHit pageTwelve = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "Visual rulebook page 12", placeholder, 12, 12, 0.01);
        Map<Integer, RuleEvidenceHit> pages = Map.of(
                2, pageTwo, 3, pageThree, 4, pageFour, 5, pageFive, 7, pageSeven, 12, pageTwelve);
        List<String> visualQueries = new java.util.ArrayList<>();
        VisualRulebookPageFactSearch visualFacts = new VisualRulebookPageFactSearch() {
            private int calls;

            @Override
            public List<PageFactMatch> search(UUID documentVersionId, String query, int limit) {
                visualQueries.add(query);
                return switch (calls++) {
                    case 0 -> List.of(visualFact(2, "generic overview", "Generic overview", 100),
                            visualFact(3, "generic setup", "Generic setup", 90));
                    case 1 -> List.of(visualFact(4, "generic components", "Generic components", 80),
                            visualFact(5, "generic cards", "Generic cards", 70));
                    case 2 -> List.of(
                            visualFact(12, "active passive rewards", "Active players take station rewards; passive players take deployed rewards.", 10),
                            visualFact(7, "deployed rewards", "Each player independently allocates dice and claims applicable rewards.", 9));
                    default -> List.of(visualFact(4, "generic components", "Generic components", 80));
                };
            }
        };
        RuleEvidenceLookup pageLookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findByPageNumbers(UUID documentVersionId, Set<Integer> pageNumbers) {
                return pageNumbers.stream().map(pages::get).toList();
            }
        };
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
                return List.of("generic dice overview", "generic card reward overview");
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(visualQueries).contains("主动玩家掷出骰子后，其他被动玩家能领取自己的已部署奖励吗");
                assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::pageFrom).contains(12, 7);
                return new ModelDraft(
                        "被动玩家按同一骰子结果领取已部署奖励。",
                        "主动玩家领取站点奖励；被动玩家领取自己的已部署奖励。",
                        List.of(pageTwelve.chunkId(), pageSeven.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (documentVersionId, query, options) -> pages.values().stream()
                        .map(source -> new HybridEvidenceHit(source, 0.01, 1, null, false))
                        .toList(),
                visualFacts,
                pageLookup,
                model);

        var answer = service.answer(
                "主动玩家掷出骰子后，其他被动玩家能领取自己的已部署奖励吗",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(12, 7);
    }

    @Test
    void sendsOnlyTheQuestionContractToTheAnswerModel() {
        RuleEvidenceHit source = evidence("ACTIONS");
        UUID expansionId = UUID.randomUUID();
        AtomicReference<RuleAnswerModel.ModelRequest> captured = new AtomicReference<>();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    captured.set(request);
                    return new ModelDraft(
                            "可以执行。", "在行动阶段支付规则所列费用后执行。",
                            List.of(source.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(captured.get().questionType())
                .isEqualTo(com.rulepilot.assistant.domain.QuestionType.RULE_QUERY);
        assertThat(captured.get().context().previousQuestion()).isEqualTo("not provided");
    }

    @Test
    void honorsModelAbstentionWithoutPublishingGeneratedClaims() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        false,
                        "The evidence describes coins but not the requested bonus.",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        "LOW"));

        var answer = service.answer(
                "How is the hidden bonus scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("未能直接回答");
        assertThat(answer.shortVerdict()).doesNotContain("hidden bonus", "coins");
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).contains(source.chunkId());
    }

    @Test
    void retriesAnOrdinaryQuestionWhenRetrievedEvidenceDirectlyResolvesAnInitialAbstention() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Tidal gate",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "uncertain", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                return new ModelDraft(
                        "先升起船帆；之后才能通过。",
                        "通过费用与进入当前航道的费用相同。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.04, 1, 1, true)), model);

        var answer = service.answer(
                "Can a ship cross the tidal gate?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(revisions).hasValue(1);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
    }

    @Test
    void putsModelProvidedCrossLanguageSearchPhrasesAheadOfSurfaceLanguageQueries() {
        RuleEvidenceHit directSource = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Wild suits",
                "A wild card may be treated as any required suit when matching an action.",
                6, 6, 0.9);
        RuleEvidenceHit unrelatedSource = evidence("ACTIONS");
        AtomicReference<String> firstQuery = new AtomicReference<>();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
                assertThat(request.question()).isEqualTo("万能牌能匹配行动花色吗？");
                return List.of("wild card matching action suit");
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                        .contains(directSource.chunkId());
                return new ModelDraft(
                        "可以按规则作为所需花色。",
                        "匹配行动时，万能牌可以视为任何所需花色。",
                        List.of(directSource.chunkId()), List.of(), "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> {
                    firstQuery.compareAndSet(null, query);
                    return query.equals("wild card matching action suit")
                            ? List.of(new HybridEvidenceHit(directSource, 0.09, 1, 1, true))
                            : List.of(new HybridEvidenceHit(unrelatedSource, 0.03, 1, null, false));
                }, model);

        var answer = service.answer(
                "万能牌能匹配行动花色吗？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(firstQuery).hasValue("wild card matching action suit");
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(6);
    }

    @Test
    void rejectsCitationThatWasNotRetrieved() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft("Unsupported", "Unsupported", List.of(UUID.randomUUID()), List.of(), "HIGH"));

        var answer = service.answer(
                "How is scoring resolved?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void repairsOnceWhenTheFirstDraftCitesEvidenceOutsideTheRetrievedScope() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger calls = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                calls.incrementAndGet();
                return new ModelDraft(
                        "Coins score one point.",
                        "Each coin scores one point.",
                        List.of(UUID.randomUUID()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                calls.incrementAndGet();
                assertThat(feedback).anyMatch(value -> value.contains("citationIds"));
                return new ModelDraft(
                        "Coins score one point.",
                        "The cited scoring rule assigns one point to each coin.",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                model);

        var answer = service.answer("How is scoring resolved?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
        assertThat(calls).hasValue(2);
    }

    @Test
    void missingContextStopsBeforeRetrievalAndModel() {
        AtomicBoolean called = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> {
                    called.set(true);
                    return List.of();
                },
                request -> {
                    called.set(true);
                    return null;
                });

        var answer = service.answer(
                "Can I play this card from my hand?",
                new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification())
                .contains("object being resolved", "when it happens")
                .doesNotContain("SITUATION_DETAILS");
        assertThat(called).isFalse();
    }

    @Test
    void unresolvedObjectStopsBeforeRetrievalWithAPlayerReadableQuestion() {
        AtomicBoolean called = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> {
                    called.set(true);
                    return List.of();
                },
                request -> {
                    called.set(true);
                    return null;
                });

        var answer = service.answer(
                "When does this trigger?",
                new QuestionContext(versionId, null, null, PlayerLocale.EN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.CLARIFICATION_REQUIRED);
        assertThat(answer.clarification())
                .contains("What exactly", "rulebook name")
                .doesNotContain("REFERENCED_OBJECT");
        assertThat(called).isFalse();
    }

    @Test
    void letsTheAnswerAgentResolveAPriorGroundedReferenceBeforeDeterministicClarification() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ACTIONS",
                "Marker timing",
                "The red marker triggers after the action ends.",
                4,
                4,
                0.9);
        AtomicReference<QuestionInterpretationRequest> interpretationRequest = new AtomicReference<>();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public boolean supportsQuestionInterpretation() {
                return true;
            }

            @Override
            public Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
                interpretationRequest.set(request);
                return Optional.of(new QuestionInterpretationDraft(
                        QuestionType.LESSON_STEP_FOLLOW_UP,
                        ReferenceBinding.PRIOR_GROUNDED_TURN,
                        List.of("红色标记", "这样"),
                        Set.of(),
                        List.of(
                                new com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion(
                                        "红色标记在什么时候触发？",
                                        Set.of(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.PRIOR_TURN)),
                                new com.rulepilot.assistant.RuleAnswerModel.PlannedSubquestion(
                                        "它也是这样吗？",
                                        Set.of(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.DIRECT_RULE)))));
            }

            @Override
            public ModelDraft compose(ModelRequest request) {
                assertThat(request.question()).contains("红色标记", "它也是这样吗");
                return new ModelDraft(
                        "是，在行动结束后触发。",
                        "规则明确说明红色标记在行动结束后触发。",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        List<String> retrievalQueries = new java.util.ArrayList<>();
        var service = answerService(
                (version, query, options) -> {
                    retrievalQueries.add(query);
                    return List.of(new HybridEvidenceHit(source, 0.9, 1, null, false));
                },
                model);
        QuestionContext context = new QuestionContext(
                versionId,
                null,
                null,
                PlayerLocale.ZH_CN,
                new PriorTurnReference(
                        versionId,
                        "红色标记在什么时候触发？",
                        "它在行动结束后触发。",
                        List.of(new PriorCitationReference(source.chunkId(), versionId, 4, 4))));

        var answer = service.answer("它也是这样吗？", context);

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("行动结束后");
        assertThat(interpretationRequest.get().deterministicMissingContext())
                .contains(com.rulepilot.assistant.domain.MissingQuestionContext.REFERENCED_OBJECT);
        assertThat(retrievalQueries)
                .anySatisfy(query -> assertThat(query).contains("红色标记在什么时候触发", "follow-up dependency"))
                .anySatisfy(query -> assertThat(query).contains("它也是这样吗", "direct rule clause"));
    }

    @Test
    void refusesWhenNoEvidenceWasRetrieved() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "What does this unknown symbol do?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void reportsUnavailableRetrievalWithoutCallingTheAnswerModel() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> {
                    throw new IllegalStateException("search temporarily unavailable");
                },
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).isEqualTo("规则检索暂时不可用，尚未生成答案。");
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void retainsTextEvidenceWhenAnOptionalVisualLookupFails() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                (documentVersionId, query, limit) -> {
                    throw new IllegalStateException("visual index temporarily unavailable");
                },
                (documentVersionId, chunkIds) -> List.of(),
                request -> new ModelDraft(
                        "每枚硬币一分。", "计算最终分数时，每枚硬币计一分。",
                        List.of(source.chunkId()), List.of(), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
    }

    @Test
    void answersFromContextualSupplementaryRetrievalWhenPrimaryHasNoMatch() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        assertThat(options.sectionTypes()).isEmpty();
                        return List.of();
                    }
                    assertThat(query)
                            .contains("rule definition timing restriction exception")
                            .doesNotContain("ACTION PHASE", "4 players");
                    assertThat(options.sectionTypes()).contains("ACTIONS");
                    return List.of(new HybridEvidenceHit(source, 0.03, 1, null, true));
                },
                request -> new ModelDraft(
                        "可以执行。", "行动阶段允许执行该行动。",
                        List.of(source.chunkId()), List.of(), "MEDIUM"));

        var answer = service.answer(
                "Can I take this action now?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void keepsHighestScoringEvidenceAcrossRetrievalIntents() {
        RuleEvidenceHit relevant = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "Player setup",
                "Players begin with four credits.", 6, 6, 0.8);
        List<HybridEvidenceHit> primary = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new HybridEvidenceHit(
                        new RuleEvidenceHit(
                                UUID.randomUUID(), versionId, "SETUP", "Unrelated " + index,
                                "Unrelated setup detail " + index, 10 + index, 10 + index, 0.2),
                        0.01 + index * 0.001, index + 1, null, false))
                .toList();
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> retrievalCalls.getAndIncrement() == 0
                        ? primary
                        : List.of(new HybridEvidenceHit(relevant, 0.05, 1, 1, true)),
                request -> {
                    assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                            .contains(relevant.chunkId())
                            .doesNotContain(primary.get(1).evidence().chunkId());
                    return new ModelDraft(
                            "开局有 4 信用点。", "玩家开局获得 4 信用点。",
                            List.of(relevant.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "开局有多少信用点？", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(relevant.chunkId());
    }

    @Test
    void letsDifferentRetrievalIntentsContributeDistinctEvidence() {
        RuleEvidenceHit ending = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "END_CONDITIONS", "Game end",
                "The game ends after the final round.", 20, 20, 0.8);
        RuleEvidenceHit ties = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "TIE_BREAKERS", "Ties",
                "The tied player with more credits wins.", 21, 21, 0.8);
        var repeatedRanking = List.of(
                new HybridEvidenceHit(ending, 0.04, 1, 1, false),
                new HybridEvidenceHit(ties, 0.03, 2, 2, false));
        var service = answerService(
                (version, query, options) -> repeatedRanking,
                request -> {
                    assertThat(request.evidence()).extracting(RuleAnswerModel.EvidenceInput::chunkId)
                            .contains(ending.chunkId(), ties.chunkId());
                    return new ModelDraft(
                            "最终轮后游戏结束；同分时比较信用点。",
                            "游戏在最终轮后结束，同分玩家比较信用点。",
                            List.of(ending.chunkId(), ties.chunkId()), List.of(), "HIGH");
                });

        var answer = service.answer(
                "When does the game end, and how are ties resolved?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId())
                .containsExactly(ending.chunkId(), ties.chunkId());
    }

    @Test
    void keepsPrimaryEvidenceWhenSupplementaryRetrievalFails() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger retrievalCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> {
                    if (retrievalCalls.getAndIncrement() == 0) {
                        return List.of(new HybridEvidenceHit(source, 0.03, 1, null, false));
                    }
                    throw new IllegalStateException("supplementary retrieval unavailable");
                },
                request -> new ModelDraft(
                        "每枚硬币一分。", "计算最终分数时，每枚硬币计一分。",
                        List.of(source.chunkId()), List.of(), "HIGH"));

        var answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(retrievalCalls).hasValue(2);
    }

    @Test
    void reportsModelTimeoutWithoutLeakingAnswerContent() {
        RuleEvidenceHit source = evidence("ACTIONS");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    throw new RuleAnswerModelTimeoutException("provider details", new RuntimeException("secret"));
                });

        var answer = service.answer(
                "Which actions are available during a turn?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.MODEL_TIMEOUT);
        assertThat(answer.shortVerdict()).doesNotContain("provider details", "secret");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void rejectsVersionConflictBeforeCallingModel() {
        UUID otherVersion = UUID.randomUUID();
        RuleEvidenceHit wrongVersion = new RuleEvidenceHit(
                UUID.randomUUID(), otherVersion, "SCORING", "Scoring", "One point.", 2, 2, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(wrongVersion, 0.03, 1, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.VERSION_CONFLICT);
        assertThat(answer.citations()).isEmpty();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void refusesConflictingSnapshotsBeforeCallingModel() {
        UUID chunkId = UUID.randomUUID();
        RuleEvidenceHit first = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores one point.", 8, 8, 0.8);
        RuleEvidenceHit conflicting = new RuleEvidenceHit(
                chunkId, versionId, "SCORING", "Scoring", "Each coin scores two points.", 8, 8, 0.7);
        AtomicBoolean modelCalled = new AtomicBoolean();
        var service = answerService(
                (version, query, options) -> List.of(
                        new HybridEvidenceHit(first, 0.03, 1, null, false),
                        new HybridEvidenceHit(conflicting, 0.02, 2, null, false)),
                request -> {
                    modelCalled.set(true);
                    return null;
                });

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(answer.shortVerdict()).contains("冲突");
        assertThat(modelCalled).isFalse();
    }

    @Test
    void blocksLowConfidenceAnswerRejectedByCritic() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic rejectingCritic = (request, risk) -> {
            criticCalls.incrementAndGet();
            assertThat(request.taskContext().objective()).contains("how does scoring work?");
            assertThat(request.taskContext().requiredCoverage())
                    .contains("RULE_QUERY")
                    .doesNotContain("player count", "game phase");
            return new GeneratedContentCritic.Review(true, List.of(new Issue(
                    IssueType.OVERREACH, 1, List.of(source.chunkId()), "The conclusion exceeds the evidence.")));
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "Coins always decide the winner.", "Coins decide every tie.",
                        List.of(source.chunkId()), List.of(), "LOW"),
                rejectingCritic);

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.INVALID_MODEL_OUTPUT);
        assertThat(answer.shortVerdict()).contains("一致性审查");
        assertThat(answer.citations()).isEmpty();
        assertThat(criticCalls).hasValue(1);
    }

    @Test
    void publishesAnEvidenceScopedLowConfidenceAnswerWithAWarning() {
        RuleEvidenceHit source = evidence("SCORING");
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "Coins score one point.",
                        "The cited scoring rule assigns one point to each coin.",
                        List.of(source.chunkId()),
                        List.of(),
                        "LOW"),
                acceptedCritic());

        var answer = service.answer(
                "How does scoring work?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED_WITH_WARNING);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(source.chunkId());
        assertThat(answer.warnings())
                .extracting(warning -> warning.type())
                .containsExactly(com.rulepilot.assistant.domain.AnswerWarning.Type.LOW_CONFIDENCE);
    }

    @Test
    void alwaysCritiquesContextResolvedFollowUps() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicReference<GeneratedContentCritic.ReviewRisk> capturedRisk = new AtomicReference<>();
        GeneratedContentCritic recordingCritic = (request, risk) -> {
            capturedRisk.set(risk);
            assertThat(request.taskContext().objective())
                    .contains("那还能再做一次吗", "执行一次主要行动后还能执行自由行动吗");
            assertThat(request.taskContext().requiredCoverage()).contains("repeatability claim");
            return new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> new ModelDraft(
                        "可以继续。", "规则允许在主要行动后执行自由行动。",
                        List.of(source.chunkId()), List.of(), "HIGH"),
                recordingCritic);

        var answer = service.answer(
                "那还能再做一次吗？",
                new QuestionContext(versionId, "执行一次主要行动后还能执行自由行动吗？", null, PlayerLocale.ZH_CN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(capturedRisk.get()).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void repairsARejectedContextResolvedFollowUpWithBoundedCriticFeedback() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "可以不限次数执行。", "主要行动后可以任意次执行自由行动。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).containsExactly(
                        "OVERREACH: Evidence establishes timing but not unlimited frequency.");
                return new ModelDraft(
                        "自由行动可以在主要行动后执行。",
                        "规则只说明时机；现有证据没有说明可以重复多少次。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> {
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            return criticCalls.getAndIncrement() == 0
                    ? new GeneratedContentCritic.Review(true, List.of(new Issue(
                            IssueType.OVERREACH,
                            1,
                            List.of(source.chunkId()),
                            "Evidence establishes timing but not unlimited frequency.")))
                    : new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                model,
                critic);

        var answer = service.answer(
                "那还能再做一次吗？",
                new QuestionContext(versionId, "执行一次主要行动后还能执行自由行动吗？", null, PlayerLocale.ZH_CN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("没有说明可以重复多少次");
        assertThat(revisions).hasValue(1);
        assertThat(criticCalls).hasValue(2);
    }

    @Test
    void passesThePlayerLearningIntentToCompositionAndCritique() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicReference<RuleAnswerModel.ModelRequest> modelRequest = new AtomicReference<>();
        AtomicReference<GeneratedContentCritic.ReviewRequest> criticRequest = new AtomicReference<>();
        AtomicReference<GeneratedContentCritic.ReviewRisk> criticRisk = new AtomicReference<>();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                request -> {
                    modelRequest.set(request);
                    return new ModelDraft(
                            "记住一个重点。", "先支付费用，再执行行动结果。",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                (request, risk) -> {
                    criticRequest.set(request);
                    criticRisk.set(risk);
                    return new GeneratedContentCritic.Review(false, List.of());
                });

        var answer = service.answer(
                "请重新检索并核对上一条回答。",
                new QuestionContext(versionId, "这个行动什么时候结算？", LearningIntent.VERIFY, PlayerLocale.ZH_CN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(modelRequest.get().context().learningIntent()).isEqualTo(LearningIntent.VERIFY);
        assertThat(criticRequest.get().taskContext().requiredCoverage()).contains("VERIFY");
        assertThat(criticRisk.get()).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
    }

    @Test
    void revisesRejectedLearningResponseWithBoundedCriticFeedback() {
        RuleEvidenceHit source = evidence("ACTIONS");
        AtomicInteger compositions = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        AtomicReference<List<String>> revisionFeedback = new AtomicReference<>();
        RuleAnswerModel adaptiveModel = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                compositions.incrementAndGet();
                return new ModelDraft(
                        "可以不限次数执行。", "可以在主要行动后任意次执行自由行动。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                revisionFeedback.set(feedback);
                return new ModelDraft(
                        "自由行动可以在主要行动后执行。",
                        "规则只说明自由行动的时机；现有证据没有说明可重复多少次。",
                        List.of(source.chunkId()), List.of(), "HIGH");
            }
        };
        AtomicInteger criticCalls = new AtomicInteger();
        GeneratedContentCritic correctingCritic = (request, risk) -> {
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            if (criticCalls.getAndIncrement() == 0) {
                return new GeneratedContentCritic.Review(true, List.of(new Issue(
                        IssueType.OVERREACH,
                        1,
                        List.of(source.chunkId()),
                        "Evidence establishes timing but not unlimited frequency.")));
            }
            assertThat(request.claims()).extracting(GeneratedContentCritic.Claim::text)
                    .noneMatch(claim -> claim.contains("不限次数") || claim.contains("任意次"));
            return new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                adaptiveModel,
                correctingCritic);

        var answer = service.answer(
                "请讲简单一点。",
                new QuestionContext(versionId, null, LearningIntent.SIMPLIFY, PlayerLocale.ZH_CN));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.explanation()).contains("没有说明可重复多少次");
        assertThat(compositions).hasValue(1);
        assertThat(revisions).hasValue(1);
        assertThat(criticCalls).hasValue(2);
        assertThat(revisionFeedback.get()).containsExactly(
                "OVERREACH: Evidence establishes timing but not unlimited frequency.");
    }

    @Test
    void critiquesAndRepairsAnExplicitConditionalRuleQuestionWithoutUsingSessionState() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "Tidal gate",
                "A ship may cross the tidal gate only after raising its sail. Its crossing cost is the same as "
                        + "the cost of entering the current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        true,
                        null,
                        "能否通过取决于船帆状态；通过时固定支付3枚硬币。",
                        "但无需实际检查船帆状态。",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "GROUNDED_APPLICATION",
                        List.of(),
                        List.of(new SituationCheckRequest(
                                "The ship has raised its sail.",
                                "NOT_PROVIDED",
                                "",
                                List.of(source.chunkId()))));
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).anyMatch(message -> message.contains("prerequisite and relative cost"));
                return new ModelDraft(
                        true,
                        null,
                        "是否能通过取决于船帆是否升起；升起后费用与进入当前航道相同。",
                        "问题尚未提供船帆状态；潮汐门使用相对费用，不能脱离当前航道写成固定数字。",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "GROUNDED_APPLICATION",
                        List.of(),
                        List.of(new SituationCheckRequest(
                                "The ship has raised its sail.",
                                "NOT_PROVIDED",
                                "",
                                List.of(source.chunkId()))));
            }
        };
        AtomicInteger reviews = new AtomicInteger();
        GeneratedContentCritic critic = (request, risk) -> {
            assertThat(risk).isEqualTo(GeneratedContentCritic.ReviewRisk.HIGH_IMPACT);
            int call = reviews.getAndIncrement();
            return call == 0
                    ? new GeneratedContentCritic.Review(true, List.of(new Issue(
                            IssueType.CONTRADICTION,
                            1,
                            List.of(source.chunkId()),
                            "The prerequisite and relative cost are replaced by unsupported claims.")))
                    : new GeneratedContentCritic.Review(true, List.of());
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, true)),
                model,
                critic);

        var answer = service.answer(
                "我的船现在能穿过潮汐门吗，费用是多少？",
                new QuestionContext(versionId),
                "alice",
                UUID.randomUUID());

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.shortVerdict()).contains("取决于", "相同");
        assertThat(revisions).hasValue(1);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void reconsidersALiveTableAbstentionUsingOnlyTheCurrentDocumentsEvidence() {
        RuleEvidenceHit source = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "ACTIONS", "TIDAL GATE",
                "After raising its sail, a ship may cross the tidal gate. The cost is the same as entering the "
                        + "current channel.",
                12, 12, 0.9);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(false, "uncertain", null, null, List.of(), List.of(), "LOW");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                revisions.incrementAndGet();
                assertThat(feedback).singleElement().asString()
                        .contains("EVIDENCE_SUFFICIENCY", "conditional branch", "relative rules");
                return new ModelDraft(
                        true,
                        null,
                        "未升起船帆前不能通过；升起后费用与进入当前航道相同。",
                        "先检查船帆条件，再沿用当前航道的进入费用。",
                        List.of(source.chunkId()),
                        List.of(),
                        "HIGH",
                        "GROUNDED_APPLICATION",
                        List.of(),
                        List.of(new SituationCheckRequest(
                                "The ship has raised its sail.",
                                "CONTRADICTED",
                                "我还没有升起船帆",
                                List.of(source.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.04, 1, 1, true)),
                model);

        var answer = service.answer(
                "我还没有升起船帆，现在能穿过潮汐门吗？之后费用怎么算？",
                new QuestionContext(versionId),
                "alice",
                UUID.randomUUID());

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.pageFrom()).containsExactly(12);
        assertThat(revisions).hasValue(1);
    }

    @Test
    void returnsOwnedConfirmedRulingBeforeCacheRetrievalAndModel() {
        UUID rulingId = UUID.randomUUID();
        UUID expansionId = UUID.randomUUID();
        RuleEvidenceHit source = evidence("SCORING");
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ConfirmedRulingLookup lookup = (documentVersionId, expansionIds, question, username) -> {
            assertThat(documentVersionId).isEqualTo(versionId);
            assertThat(expansionIds).isEmpty();
            assertThat(question).isEqualTo("how are coins scored?");
            assertThat(username).isEqualTo("alice");
            return Optional.of(new ConfirmedRulingLookup.ConfirmedAnswer(
                    rulingId,
                    versionId,
                    "Use the confirmed score.",
                    "Each remaining coin scores one point.",
                    List.of(new ConfirmedRulingLookup.Citation(
                            source.chunkId(), versionId, source.sectionType(), source.heading(), source.excerpt(), 8, 8)),
                    List.of(),
                    "HIGH",
                    false,
                    4));
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    downstreamCalled.set(true);
                    return List.of();
                },
                request -> {
                    downstreamCalled.set(true);
                    return null;
                },
                new InMemoryAnswerCache(),
                rateLimiter,
                new MutableRuleDataVersion(),
                lookup,
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?",
                new QuestionContext(versionId),
                "alice",
                null);

        assertThat(answer.shortVerdict()).isEqualTo("Use the confirmed score.");
        assertThat(answer.confirmedRulingId()).isEqualTo(rulingId);
        assertThat(answer.confirmedRulingVersion()).isEqualTo(4);
        assertThat(downstreamCalled).isFalse();
        assertThat(rateLimiter.userChecks).isZero();
        assertThat(metrics.counter("rulepilot.answer.requests", "source", "confirmed-ruling").count())
                .isEqualTo(1);
    }

    @Test
    void naturallyMissesOldCacheEntryAfterRuleDataVersionChanges() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        RecordingRateLimiter rateLimiter = new RecordingRateLimiter();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        MutableRuleDataVersion versions = new MutableRuleDataVersion();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                cache,
                rateLimiter,
                versions,
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);
        QuestionContext context = new QuestionContext(versionId);

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);
        versions.increment(versionId);
        StructuredRuleAnswer afterRuleChange = service.answer("How are coins scored?", context);

        assertThat(second).isEqualTo(first);
        assertThat(afterRuleChange).isEqualTo(first);
        assertThat(modelCalls).hasValue(2);
        assertThat(rateLimiter.userChecks).isEqualTo(3);
        assertThat(rateLimiter.modelAcquires).isEqualTo(2);
        assertThat(rateLimiter.releases).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "miss").count()).isEqualTo(2);
        assertThat(metrics.counter("rulepilot.answer.cache.requests", "result", "hit").count()).isEqualTo(1);
    }

    @Test
    void reusesOneGroundedAnswerAcrossDifferentSessionIdsAndLegacyTableState() {
        RuleEvidenceHit source = evidence("SCORING");
        AtomicInteger modelCalls = new AtomicInteger();
        var service = answerService(
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.",
                            "Each coin contributes one point.",
                            List.of(source.chunkId()),
                            List.of(),
                            "HIGH");
                });

        StructuredRuleAnswer first = service.answer(
                "How are coins scored?",
                new QuestionContext(versionId),
                "alice",
                UUID.randomUUID());
        StructuredRuleAnswer second = service.answer(
                "How are coins scored?",
                new QuestionContext(versionId),
                "alice",
                UUID.randomUUID());

        assertThat(second).isEqualTo(first);
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    void retrievesAndReturnsValidatedAnswerWhenCacheIsUnavailable() {
        RuleEvidenceHit source = evidence("SCORING");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AtomicInteger modelCalls = new AtomicInteger();
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                new UnavailableAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                metrics);

        StructuredRuleAnswer answer = service.answer(
                "How are coins scored?", new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).hasSize(1);
        assertThat(modelCalls).hasValue(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "read").count()).isEqualTo(1);
        assertThat(metrics.counter("rulepilot.answer.cache.errors", "operation", "write").count()).isEqualTo(1);
    }

    @Test
    void bypassesTheCacheWhenRuleDataVersionIsUnavailableButEvidenceIsReadable() {
        RuleEvidenceHit source = evidence("SCORING");
        InMemoryAnswerCache cache = new InMemoryAnswerCache();
        AtomicInteger modelCalls = new AtomicInteger();
        RuleDataVersion unavailableVersion = new RuleDataVersion() {
            @Override
            public long current(UUID documentVersionId) {
                throw new IllegalArgumentException("document version does not exist");
            }

            @Override
            public long increment(UUID documentVersionId) {
                throw new UnsupportedOperationException("rule data is unavailable");
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> List.of(new HybridEvidenceHit(source, 0.03, 1, null, false)),
                request -> {
                    modelCalls.incrementAndGet();
                    return new ModelDraft(
                            "Coins score one point.", "Each coin contributes one point.",
                            List.of(source.chunkId()), List.of(), "HIGH");
                },
                cache,
                new RecordingRateLimiter(),
                unavailableVersion,
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
        QuestionContext context = new QuestionContext(versionId);

        StructuredRuleAnswer first = service.answer("How are coins scored?", context);
        StructuredRuleAnswer second = service.answer("How are coins scored?", context);

        assertThat(first.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(second.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(modelCalls).hasValue(2);
        assertThat(cache.values).isEmpty();
    }

    @Test
    void repairsAConditionalAnswerUntilItCitesTheMostDirectCurrentEvidence() {
        RuleEvidenceHit setupOnly = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "SETUP", "事件牌堆", "将事件牌堆放在版图旁边。", 3, 3, 0.9);
        RuleEvidenceHit turnProcedure = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "GENERAL", "回合结束", "结束自己的回合后，抽取一张事件牌并结算其效果。", 6, 6, 0.8);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "Draw an event card.",
                        "After ending your turn, draw and resolve the event.",
                        List.of(setupOnly.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                assertThat(feedback).anyMatch(item -> item.startsWith("DIRECT_CONDITION_CITATION"));
                revisions.incrementAndGet();
                return new ModelDraft(
                        "Draw an event card and resolve it.",
                        "After ending your turn, draw the event card and carry out its effect.",
                        List.of(turnProcedure.chunkId()),
                        List.of(),
                        "HIGH");
            }
        };
        var service = answerService(
                (version, query, options) -> query.contains("condition procedure consequence")
                        ? List.of(
                                new HybridEvidenceHit(setupOnly, 0.04, 1, null, false),
                                new HybridEvidenceHit(turnProcedure, 0.03, 2, null, false))
                        : List.of(new HybridEvidenceHit(setupOnly, 0.04, 1, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "我结束自己的回合后，事件牌要怎样处理？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(turnProcedure.chunkId());
        assertThat(revisions).hasValue(1);
    }

    @Test
    void repairsAnEndgameAnswerUntilItCitesTheDecisiveResolutionSequence() {
        RuleEvidenceHit componentOnly = new RuleEvidenceHit(
                UUID.randomUUID(), versionId, "COMPONENTS", "Cargo", "Cargo cards are used by the Smugglers.", 3, 3, 0.9);
        RuleEvidenceHit endgameProcedure = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ROUND_STRUCTURE",
                "Clean Up",
                "1. End EGavmen? If any player has at least 30\u00a0Fame, end the game. Smugglers score pledged Cargo, then the player with the most Fame wins. On a tie,\nthe tied player with the most gold wins.",
                9,
                9,
                0.8);
        AtomicInteger revisions = new AtomicInteger();
        RuleAnswerModel model = new RuleAnswerModel() {
            @Override
            public ModelDraft compose(ModelRequest request) {
                return new ModelDraft(
                        "After End Rewards, the game ends.",
                        "Cargo is scored later.",
                        List.of(componentOnly.chunkId()),
                        List.of(),
                        "HIGH");
            }

            @Override
            public ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
                assertThat(feedback).anyMatch(item -> item.startsWith("ENDGAME_RESOLUTION_CITATION"));
                revisions.incrementAndGet();
                return new ModelDraft(
                        true, null,
                        "在轮末清理时检查30名声；触发后由走私者计分承诺货物，再比较名声与金币。",
                        "清理的结束检查触发后，走私者先结算承诺货物；名声最高者获胜，平局时比较金币。",
                        List.of(componentOnly.chunkId(), endgameProcedure.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(),
                        List.of(new RuleTieRequest(
                                "名声最高者出现平局。",
                                List.of("比较平局玩家的金币。"),
                                "金币最多的平局玩家获胜。",
                                "SINGLE_TIEBREAKER",
                                List.of(endgameProcedure.chunkId()))));
            }
        };
        var service = answerService(
                (version, query, options) -> List.of(
                        new HybridEvidenceHit(componentOnly, 0.04, 1, null, false),
                        new HybridEvidenceHit(endgameProcedure, 0.03, 2, null, false)),
                model);

        StructuredRuleAnswer answer = service.answer(
                "有人达到30名声后要立刻结束吗？承诺货物何时计分，平局如何处理？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(endgameProcedure.chunkId());
        assertThat(revisions).hasValue(1);
    }

    @Test
    void completesAnEndgameAnswerWithTheAdjacentResolutionSequence() {
        RuleEvidenceHit roundEnd = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ROUND_STRUCTURE",
                "Playing the Game",
                "The game can end at the end of a round after scoring regions.",
                8,
                8,
                0.8);
        RuleEvidenceHit endgameProcedure = new RuleEvidenceHit(
                UUID.randomUUID(),
                versionId,
                "ROUND_STRUCTURE",
                "Ending the Round",
                "1. End EGavmen? If any player has at least 30\u00a0Fame, end the game. "
                        + "Smugglers score pledged Cargo, then the player with the most Fame wins. "
                        + "On a tie,\nthe tied player with the most gold wins.",
                9,
                9,
                0.8);
        RuleEvidenceLookup lookup = new RuleEvidenceLookup() {
            @Override
            public List<RuleEvidenceHit> findByChunkIds(UUID documentVersionId, Set<UUID> chunkIds) {
                return List.of();
            }

            @Override
            public List<RuleEvidenceHit> findAdjacent(
                    UUID documentVersionId, Set<UUID> anchorChunkIds, int radius, Set<String> sectionTypes) {
                assertThat(anchorChunkIds).contains(roundEnd.chunkId());
                assertThat(radius).isEqualTo(2);
                return List.of(endgameProcedure);
            }
        };
        List<RuleEvidenceHit> distractors = List.of(
                new RuleEvidenceHit(UUID.randomUUID(), versionId, "COMPONENTS", "Cargo", "Cargo cards are secret.", 3, 3, 0.7),
                new RuleEvidenceHit(UUID.randomUUID(), versionId, "SETUP", "Setup", "Put gold near the board.", 4, 4, 0.7),
                new RuleEvidenceHit(UUID.randomUUID(), versionId, "ACTIONS", "Move", "Move one space.", 5, 5, 0.7),
                new RuleEvidenceHit(UUID.randomUUID(), versionId, "SCORING", "Fame", "Fame is tracked on the board.", 6, 6, 0.7));
        AtomicInteger retrievals = new AtomicInteger();
        RuleAnswerModel model = request -> {
            assertThat(request.evidence()).extracting(evidence -> evidence.chunkId())
                    .contains(endgameProcedure.chunkId());
            return new ModelDraft(
                    true, null,
                    "不是立刻结束；轮末区域计分后才检查30名声。",
                    "触发后先计分承诺货物，再比较名声；平局比较金币。",
                    List.of(endgameProcedure.chunkId()), List.of(), "HIGH", "DIRECT_RULE",
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(new RuleTieRequest(
                            "名声最高者出现平局。",
                            List.of("比较平局玩家的金币。"),
                            "金币最多的平局玩家获胜。",
                            "SINGLE_TIEBREAKER",
                            List.of(endgameProcedure.chunkId()))));
        };
        var service = answerService(
                (version, query, options) -> {
                    int index = retrievals.getAndIncrement();
                    RuleEvidenceHit source = index == 0
                            ? roundEnd
                            : distractors.get(Math.min(index - 1, distractors.size() - 1));
                    return List.of(new HybridEvidenceHit(source, 0.04, 1, null, false));
                },
                VisualRulebookPageFactSearch.empty(),
                lookup,
                model);

        StructuredRuleAnswer answer = service.answer(
                "有人达到30名声后要立刻结束吗？承诺货物何时计分，平局如何处理？",
                new QuestionContext(versionId));

        assertThat(answer.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(answer.citations()).extracting(citation -> citation.chunkId()).containsExactly(endgameProcedure.chunkId());
    }

    @Test
    void stopsBeforeRetrievalWhenRateLimitStorageIsUnavailable() {
        AtomicBoolean retrievalCalled = new AtomicBoolean();
        RuleAnswerRateLimiter unavailableLimiter = new RuleAnswerRateLimiter() {
            @Override
            public void checkUser(String username) {
                throw new RuleAnswerRateLimitUnavailableException(5, new IllegalStateException("Redis unavailable"));
            }

            @Override
            public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
                throw new AssertionError("model permit must not be acquired");
            }
        };
        var service = new StructuredRuleAnswerService(
                understanding,
                (version, query, options) -> {
                    retrievalCalled.set(true);
                    return List.of();
                },
                request -> null,
                new InMemoryAnswerCache(),
                unavailableLimiter,
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.answer(
                        "How are coins scored?", new QuestionContext(versionId)))
                .isInstanceOf(RuleAnswerRateLimitUnavailableException.class);
        assertThat(retrievalCalled).isFalse();
    }

    private StructuredRuleAnswerService answerService(HybridRuleSearch retrieval, RuleAnswerModel model) {
        return answerService(retrieval, model, acceptedCritic());
    }

    private StructuredRuleAnswerService answerService(
            HybridRuleSearch retrieval, RuleAnswerModel model, GeneratedContentCritic critic) {
        return new StructuredRuleAnswerService(
                understanding, retrieval, model, new InMemoryAnswerCache(), new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                critic,
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    private StructuredRuleAnswerService answerService(
            HybridRuleSearch retrieval,
            VisualRulebookPageFactSearch visualFacts,
            RuleEvidenceLookup evidenceLookup,
            RuleAnswerModel model) {
        return new StructuredRuleAnswerService(
                understanding,
                retrieval,
                visualFacts,
                evidenceLookup,
                model,
                new InMemoryAnswerCache(),
                new RecordingRateLimiter(),
                new MutableRuleDataVersion(),
                noConfirmedRulings(),
                new PolicyEvidenceVerifier(),
                acceptedCritic(),
                null,
                new ImmediateAuditedAgentInvocations(),
                io.micrometer.observation.ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    private GeneratedContentCritic acceptedCritic() {
        return (request, risk) -> new GeneratedContentCritic.Review(false, List.of());
    }

    private ConfirmedRulingLookup noConfirmedRulings() {
        return (documentVersionId, expansionIds, question, username) -> Optional.empty();
    }

    private RuleEvidenceHit evidence(String sectionType) {
        return new RuleEvidenceHit(
                UUID.randomUUID(), versionId, sectionType, "Scoring", "Each coin is worth one point.", 8, 8, 0.8);
    }

    private VisualRulebookPageFactSearch.PageFactMatch visualFact(
            int page, String printedTerms, String summary, double score) {
        return new VisualRulebookPageFactSearch.PageFactMatch(
                page, printedTerms, summary, List.of(printedTerms), score);
    }

    private static final class InMemoryAnswerCache implements RuleAnswerCache {
        private final Map<AnswerCacheKey, StructuredRuleAnswer> values = new HashMap<>();

        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            values.put(key, answer);
        }
    }

    private static final class UnavailableAnswerCache implements RuleAnswerCache {
        @Override
        public Optional<StructuredRuleAnswer> find(AnswerCacheKey key) {
            throw new IllegalStateException("Redis unavailable");
        }

        @Override
        public void save(AnswerCacheKey key, StructuredRuleAnswer answer) {
            throw new IllegalStateException("Redis unavailable");
        }
    }

    private static final class RecordingRateLimiter implements RuleAnswerRateLimiter {
        private int userChecks;
        private int modelAcquires;
        private int releases;

        @Override
        public void checkUser(String username) {
            userChecks++;
        }

        @Override
        public Permit acquireModel(String username, UUID gameSessionId, String providerId) {
            modelAcquires++;
            return () -> releases++;
        }
    }

    private static final class MutableRuleDataVersion implements RuleDataVersion {
        private long value = 1;

        @Override
        public long current(UUID documentVersionId) {
            return value;
        }

        @Override
        public long increment(UUID documentVersionId) {
            return ++value;
        }
    }
}
