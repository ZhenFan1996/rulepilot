package com.rulepilot.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class VersionedAgentPrompts {

    private final String teachingSystem;
    private final String focusedTeachingSystem;
    private final String teachingUser;
    private final String teachingOutlineSystem;
    private final String teachingOutlineUser;
    private final String answerSystem;
    private final String focusedAnswerCoreSystem;
    private final Map<AnswerPromptMode, String> focusedAnswerModules;
    private final String answerUser;
    private final String answerRetrievalRewriteSystem;
    private final String answerRetrievalRewriteUser;
    private final String criticSystem;
    private final String lessonStructureCriticSystem;
    private final String atomicCriticSystem;
    private final String objectiveCoverageCriticSystem;
    private final String criticUser;
    private final String structuredOutputRepair;
    private final String lessonLocalizationSystem;
    private final String lessonLocalizationUser;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v16-system.txt") Resource teachingSystem,
            @Value("classpath:prompts/teaching-agent-v17-fidelity-system.txt") Resource teachingFidelity,
            @Value("classpath:prompts/teaching-agent-v18-visual-fit-system.txt") Resource teachingVisualFit,
            @Value("classpath:prompts/teaching-agent-v19-player-language-system.txt") Resource teachingPlayerLanguage,
            @Value("classpath:prompts/teaching-agent-v20-visual-output-contract-system.txt") Resource teachingVisualOutput,
            @Value("classpath:prompts/teaching-agent-v21-complete-instructions-system.txt") Resource teachingCompleteInstructions,
            @Value("classpath:prompts/teaching-agent-v22-ordered-procedure-fidelity-system.txt") Resource teachingProcedureFidelity,
            @Value("classpath:prompts/teaching-agent-v23-chapter-scope-map-system.txt") Resource teachingChapterScope,
            @Value("classpath:prompts/teaching-agent-v24-actor-and-example-fidelity-system.txt") Resource teachingActorAndExampleFidelity,
            @Value("classpath:prompts/teaching-agent-v25-conditional-values-fidelity-system.txt") Resource teachingConditionalValues,
            @Value("classpath:prompts/teaching-agent-v26-conditional-scope-and-tie-fidelity-system.txt") Resource teachingConditionalScopeAndTie,
            @Value("classpath:prompts/teaching-agent-v27-visible-observation-system.txt") Resource teachingVisibleObservation,
            @Value("classpath:prompts/teaching-agent-v28-rulebook-scope-system.txt") Resource teachingRulebookScope,
            @Value("classpath:prompts/teaching-agent-v29-grammatical-relation-fidelity-system.txt") Resource teachingGrammaticalRelationFidelity,
            @Value("classpath:prompts/teaching-agent-v30-quantity-role-fidelity-system.txt") Resource teachingQuantityRoleFidelity,
            @Value("classpath:prompts/teaching-agent-v31-rule-dependency-fidelity-system.txt") Resource teachingRuleDependencyFidelity,
            @Value("classpath:prompts/teaching-agent-v32-player-readable-rule-dependency-system.txt") Resource teachingPlayerReadableDependency,
            @Value("classpath:prompts/teaching-agent-v33-predicate-owner-fidelity-system.txt") Resource teachingPredicateOwnerFidelity,
            @Value("classpath:prompts/teaching-agent-v34-rule-conflict-check-system.txt") Resource teachingRuleConflictCheck,
            @Value("classpath:prompts/teaching-agent-v35-self-contained-conflict-verdict-system.txt") Resource teachingSelfContainedConflictVerdict,
            @Value("classpath:prompts/teaching-agent-v36-direct-source-evidence-system.txt") Resource teachingDirectSourceEvidence,
            @Value("classpath:prompts/teaching-agent-v37-source-scope-fidelity-system.txt") Resource teachingSourceScopeFidelity,
            @Value("classpath:prompts/teaching-agent-v38-source-term-number-fidelity-system.txt") Resource teachingSourceTermNumberFidelity,
            @Value("classpath:prompts/teaching-agent-v39-source-causal-direction-system.txt") Resource teachingSourceCausalDirection,
            @Value("classpath:prompts/teaching-agent-v40-source-temporal-boundary-system.txt") Resource teachingSourceTemporalBoundary,
            @Value("classpath:prompts/teaching-agent-v41-permission-ruling-system.txt") Resource teachingPermissionRuling,
            @Value("classpath:prompts/teaching-agent-v42-focused-runtime-system.txt") Resource focusedTeachingSystem,
            @Value("classpath:prompts/teaching-agent-v11-user.txt") Resource teachingUser,
            @Value("classpath:prompts/teaching-outline-v6-system.txt") Resource teachingOutlineSystem,
            @Value("classpath:prompts/teaching-outline-v7-fidelity-system.txt") Resource teachingOutlineFidelity,
            @Value("classpath:prompts/teaching-outline-v8-visual-density-system.txt") Resource teachingOutlineVisualDensity,
            @Value("classpath:prompts/teaching-outline-v9-core-evidence-system.txt") Resource teachingOutlineCoreEvidence,
            @Value("classpath:prompts/teaching-outline-v10-chapter-ownership-system.txt") Resource teachingOutlineChapterOwnership,
            @Value("classpath:prompts/teaching-outline-v11-visual-coverage-system.txt") Resource teachingOutlineVisualCoverage,
            @Value("classpath:prompts/teaching-outline-v12-rulebook-scope-system.txt") Resource teachingOutlineRulebookScope,
            @Value("classpath:prompts/teaching-outline-v4-user.txt") Resource teachingOutlineUser,
            @Value("classpath:prompts/rule-answer-agent-v6-system.txt") Resource answerSystem,
            @Value("classpath:prompts/rule-answer-agent-v7-fidelity-system.txt") Resource answerFidelity,
            @Value("classpath:prompts/rule-answer-agent-v8-direct-rulings-system.txt") Resource answerDirectRulings,
            @Value("classpath:prompts/rule-answer-agent-v9-prohibition-fidelity-system.txt") Resource answerProhibitionFidelity,
            @Value("classpath:prompts/rule-answer-agent-v10-completeness-boundary-system.txt") Resource answerCompletenessBoundary,
            @Value("classpath:prompts/rule-answer-agent-v11-scope-discipline-system.txt") Resource answerScopeDiscipline,
            @Value("classpath:prompts/rule-answer-agent-v12-direct-clause-citations-system.txt") Resource answerDirectClauseCitations,
            @Value("classpath:prompts/rule-answer-agent-v13-grounded-application-system.txt") Resource answerGroundedApplication,
            @Value("classpath:prompts/rule-answer-agent-v14-natural-language-condition-system.txt") Resource answerNaturalLanguageCondition,
            @Value("classpath:prompts/rule-answer-agent-v16-fallback-branch-fidelity-system.txt") Resource answerFallbackBranchFidelity,
            @Value("classpath:prompts/rule-answer-agent-v17-correctable-revision-system.txt") Resource answerCorrectableRevision,
            @Value("classpath:prompts/rule-answer-agent-v18-counterfactual-follow-up-system.txt") Resource answerCounterfactualFollowUp,
            @Value("classpath:prompts/rule-answer-agent-v19-complete-list-system.txt") Resource answerCompleteList,
            @Value("classpath:prompts/rule-answer-agent-v20-identifier-binding-system.txt") Resource answerIdentifierBinding,
            @Value("classpath:prompts/rule-answer-agent-v21-resolved-visual-language-system.txt") Resource answerResolvedVisualLanguage,
            @Value("classpath:prompts/rule-answer-agent-v22-mechanical-list-consistency-system.txt") Resource answerMechanicalListConsistency,
            @Value("classpath:prompts/rule-answer-agent-v23-grounded-terminology-system.txt") Resource answerGroundedTerminology,
            @Value("classpath:prompts/rule-answer-agent-v24-grounded-calculation-system.txt") Resource answerGroundedCalculation,
            @Value("classpath:prompts/rule-answer-agent-v25-rule-relationship-fidelity-system.txt") Resource answerRuleRelationshipFidelity,
            @Value("classpath:prompts/rule-answer-agent-v26-grounded-situation-check-system.txt") Resource answerGroundedSituationCheck,
            @Value("classpath:prompts/rule-answer-agent-v27-cited-walkthrough-system.txt") Resource answerCitedWalkthrough,
            @Value("classpath:prompts/rule-answer-agent-v28-cited-decision-table-system.txt") Resource answerCitedDecisionTable,
            @Value("classpath:prompts/rule-answer-agent-v29-cited-exception-clauses-system.txt") Resource answerCitedExceptionClauses,
            @Value("classpath:prompts/rule-answer-agent-v30-exception-focus-system.txt") Resource answerExceptionFocus,
            @Value("classpath:prompts/rule-answer-agent-v31-concise-exception-verdict-system.txt") Resource answerConciseExceptionVerdict,
            @Value("classpath:prompts/rule-answer-agent-v32-self-contained-exception-clauses-system.txt") Resource answerSelfContainedExceptionClauses,
            @Value("classpath:prompts/rule-answer-agent-v33-cited-term-definitions-system.txt") Resource answerCitedTermDefinitions,
            @Value("classpath:prompts/rule-answer-agent-v34-cited-worked-examples-system.txt") Resource answerCitedWorkedExamples,
            @Value("classpath:prompts/rule-answer-agent-v35-cited-rule-priority-system.txt") Resource answerCitedRulePriority,
            @Value("classpath:prompts/rule-answer-agent-v36-cited-timing-order-system.txt") Resource answerCitedTimingOrder,
            @Value("classpath:prompts/rule-answer-agent-v37-cited-tie-resolution-system.txt") Resource answerCitedTieResolution,
            @Value("classpath:prompts/rule-answer-agent-v38-cited-rule-scope-system.txt") Resource answerCitedRuleScope,
            @Value("classpath:prompts/rule-answer-agent-v39-cited-concept-comparison-system.txt") Resource answerCitedConceptComparison,
            @Value("classpath:prompts/rule-answer-agent-v40-cited-rule-options-system.txt") Resource answerCitedRuleOptions,
            @Value("classpath:prompts/rule-answer-agent-v41-grammatical-relation-fidelity-system.txt") Resource answerGrammaticalRelationFidelity,
            @Value("classpath:prompts/rule-answer-agent-v42-quantity-role-fidelity-system.txt") Resource answerQuantityRoleFidelity,
            @Value("classpath:prompts/rule-answer-agent-v43-cited-rule-dependency-system.txt") Resource answerCitedRuleDependency,
            @Value("classpath:prompts/rule-answer-agent-v44-player-readable-rule-dependency-system.txt") Resource answerPlayerReadableDependency,
            @Value("classpath:prompts/rule-answer-agent-v45-predicate-owner-fidelity-system.txt") Resource answerPredicateOwnerFidelity,
            @Value("classpath:prompts/rule-answer-agent-v46-self-contained-why-verdict-system.txt") Resource answerSelfContainedWhyVerdict,
            @Value("classpath:prompts/rule-answer-agent-v47-cited-rule-conflict-check-system.txt") Resource answerCitedRuleConflictCheck,
            @Value("classpath:prompts/rule-answer-agent-v48-self-contained-conflict-verdict-system.txt") Resource answerSelfContainedConflictVerdict,
            @Value("classpath:prompts/rule-answer-agent-v49-direct-source-evidence-system.txt") Resource answerDirectSourceEvidence,
            @Value("classpath:prompts/rule-answer-agent-v50-source-scope-fidelity-system.txt") Resource answerSourceScopeFidelity,
            @Value("classpath:prompts/rule-answer-agent-v51-source-term-number-fidelity-system.txt") Resource answerSourceTermNumberFidelity,
            @Value("classpath:prompts/rule-answer-agent-v52-source-causal-direction-system.txt") Resource answerSourceCausalDirection,
            @Value("classpath:prompts/rule-answer-agent-v53-source-temporal-boundary-system.txt") Resource answerSourceTemporalBoundary,
            @Value("classpath:prompts/rule-answer-agent-v54-permission-ruling-system.txt") Resource answerPermissionRuling,
            @Value("classpath:prompts/rule-answer-agent-v55-focused-aid-routing-system.txt") Resource answerFocusedAidRouting,
            @Value("classpath:prompts/rule-answer-agent-v4-user.txt") Resource answerUser,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-system.txt") Resource answerRetrievalRewriteSystem,
            @Value("classpath:prompts/rule-answer-retrieval-rewrite-v1-user.txt") Resource answerRetrievalRewriteUser,
            @Value("classpath:prompts/content-critic-v7-system.txt") Resource criticSystem,
            @Value("classpath:prompts/content-critic-v8-fidelity-system.txt") Resource criticFidelity,
            @Value("classpath:prompts/content-critic-v9-answer-scope-system.txt") Resource criticAnswerScope,
            @Value("classpath:prompts/content-critic-v10-lesson-structure-system.txt") Resource criticLessonStructure,
            @Value("classpath:prompts/content-critic-v11-actor-and-example-fidelity-system.txt") Resource criticActorAndExampleFidelity,
            @Value("classpath:prompts/content-critic-v12-grammatical-relation-fidelity-system.txt") Resource criticGrammaticalRelationFidelity,
            @Value("classpath:prompts/content-critic-v13-quantity-role-fidelity-system.txt") Resource criticQuantityRoleFidelity,
            @Value("classpath:prompts/content-critic-v14-rule-dependency-fidelity-system.txt") Resource criticRuleDependencyFidelity,
            @Value("classpath:prompts/content-critic-v15-player-readable-rule-dependency-system.txt") Resource criticPlayerReadableDependency,
            @Value("classpath:prompts/content-critic-v16-predicate-owner-fidelity-system.txt") Resource criticPredicateOwnerFidelity,
            @Value("classpath:prompts/content-critic-v17-self-contained-why-verdict-system.txt") Resource criticSelfContainedWhyVerdict,
            @Value("classpath:prompts/content-critic-v18-rule-conflict-check-system.txt") Resource criticRuleConflictCheck,
            @Value("classpath:prompts/content-critic-v19-self-contained-conflict-verdict-system.txt") Resource criticSelfContainedConflictVerdict,
            @Value("classpath:prompts/content-critic-v20-direct-source-evidence-system.txt") Resource criticDirectSourceEvidence,
            @Value("classpath:prompts/content-critic-v21-source-scope-fidelity-system.txt") Resource criticSourceScopeFidelity,
            @Value("classpath:prompts/content-critic-v22-source-term-number-fidelity-system.txt") Resource criticSourceTermNumberFidelity,
            @Value("classpath:prompts/content-critic-v23-source-causal-direction-system.txt") Resource criticSourceCausalDirection,
            @Value("classpath:prompts/content-critic-v24-source-temporal-boundary-system.txt") Resource criticSourceTemporalBoundary,
            @Value("classpath:prompts/content-critic-v25-permission-ruling-system.txt") Resource criticPermissionRuling,
            @Value("classpath:prompts/atomic-content-critic-v3-system.txt") Resource atomicCriticSystem,
            @Value("classpath:prompts/objective-coverage-critic-v3-system.txt") Resource objectiveCoverageCriticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair,
            @Value("classpath:prompts/lesson-localization-v2-system.txt") Resource lessonLocalizationSystem,
            @Value("classpath:prompts/lesson-localization-v1-user.txt") Resource lessonLocalizationUser)
            throws IOException {
        this.teachingSystem = combined(
                teachingSystem,
                teachingFidelity,
                teachingVisualFit,
                teachingPlayerLanguage,
                teachingVisualOutput,
                teachingCompleteInstructions,
                teachingProcedureFidelity,
                teachingChapterScope,
                teachingActorAndExampleFidelity,
                teachingConditionalValues,
                teachingConditionalScopeAndTie,
                teachingVisibleObservation,
                teachingRulebookScope,
                teachingGrammaticalRelationFidelity,
                teachingQuantityRoleFidelity,
                teachingRuleDependencyFidelity,
                teachingPlayerReadableDependency,
                teachingPredicateOwnerFidelity,
                teachingRuleConflictCheck,
                teachingSelfContainedConflictVerdict,
                teachingDirectSourceEvidence,
                teachingSourceScopeFidelity,
                teachingSourceTermNumberFidelity,
                teachingSourceCausalDirection,
                teachingSourceTemporalBoundary,
                teachingPermissionRuling);
        this.focusedTeachingSystem = read(focusedTeachingSystem);
        this.teachingUser = read(teachingUser);
        this.teachingOutlineSystem = combined(
                teachingOutlineSystem,
                teachingOutlineFidelity,
                teachingOutlineVisualDensity,
                teachingOutlineCoreEvidence,
                teachingOutlineChapterOwnership,
                teachingOutlineVisualCoverage,
                teachingOutlineRulebookScope);
        this.teachingOutlineUser = read(teachingOutlineUser);
        this.answerSystem = combined(
                answerSystem,
                answerFidelity,
                answerDirectRulings,
                answerProhibitionFidelity,
                answerCompletenessBoundary,
                answerScopeDiscipline,
                answerDirectClauseCitations,
                answerGroundedApplication,
                answerNaturalLanguageCondition,
                answerFallbackBranchFidelity,
                answerCorrectableRevision,
                answerCounterfactualFollowUp,
                answerCompleteList,
                answerIdentifierBinding,
                answerResolvedVisualLanguage,
                answerMechanicalListConsistency,
                answerGroundedTerminology,
                answerGroundedCalculation,
                answerRuleRelationshipFidelity,
                answerGroundedSituationCheck,
                answerCitedWalkthrough,
                answerCitedDecisionTable,
                answerCitedExceptionClauses,
                answerExceptionFocus,
                answerConciseExceptionVerdict,
                answerSelfContainedExceptionClauses,
                answerCitedTermDefinitions,
                answerCitedWorkedExamples,
                answerCitedRulePriority,
                answerCitedTimingOrder,
                answerCitedTieResolution,
                answerCitedRuleScope,
                answerCitedConceptComparison,
                answerCitedRuleOptions,
                answerGrammaticalRelationFidelity,
                answerQuantityRoleFidelity,
                answerCitedRuleDependency,
                answerPlayerReadableDependency,
                answerPredicateOwnerFidelity,
                answerSelfContainedWhyVerdict,
                answerCitedRuleConflictCheck,
                answerSelfContainedConflictVerdict,
                answerDirectSourceEvidence,
                answerSourceScopeFidelity,
                answerSourceTermNumberFidelity,
                answerSourceCausalDirection,
                answerSourceTemporalBoundary,
                answerPermissionRuling,
                answerFocusedAidRouting);
        this.focusedAnswerCoreSystem = combined(
                answerSystem,
                answerFidelity,
                answerDirectRulings,
                answerProhibitionFidelity,
                answerCompletenessBoundary,
                answerScopeDiscipline,
                answerDirectClauseCitations,
                answerNaturalLanguageCondition,
                answerCorrectableRevision,
                answerCounterfactualFollowUp,
                answerIdentifierBinding,
                answerGroundedTerminology,
                answerGrammaticalRelationFidelity,
                answerQuantityRoleFidelity,
                answerPredicateOwnerFidelity,
                answerSourceScopeFidelity,
                answerSourceTermNumberFidelity,
                answerSourceCausalDirection,
                answerSourceTemporalBoundary,
                answerPermissionRuling,
                answerFocusedAidRouting);
        this.focusedAnswerModules = Map.ofEntries(
                Map.entry(AnswerPromptMode.NONE, ""),
                Map.entry(AnswerPromptMode.OPTIONS, combined(answerCompleteList, answerCitedRuleOptions)),
                Map.entry(AnswerPromptMode.VISUAL, combined(answerResolvedVisualLanguage, answerMechanicalListConsistency)),
                Map.entry(AnswerPromptMode.CALCULATION, read(answerGroundedCalculation)),
                Map.entry(AnswerPromptMode.RELATIONSHIP, combined(
                        answerRuleRelationshipFidelity,
                        answerCitedRulePriority,
                        answerCitedRuleConflictCheck,
                        answerSelfContainedConflictVerdict)),
                Map.entry(AnswerPromptMode.WALKTHROUGH, combined(
                        answerCitedWalkthrough,
                        answerCitedRuleDependency,
                        answerPlayerReadableDependency,
                        answerSelfContainedWhyVerdict)),
                Map.entry(AnswerPromptMode.DECISION_TABLE, read(answerCitedDecisionTable)),
                Map.entry(AnswerPromptMode.EXCEPTIONS, combined(
                        answerCitedExceptionClauses,
                        answerExceptionFocus,
                        answerConciseExceptionVerdict,
                        answerSelfContainedExceptionClauses)),
                Map.entry(AnswerPromptMode.DEFINITIONS, combined(
                        answerCitedTermDefinitions,
                        answerCitedConceptComparison)),
                Map.entry(AnswerPromptMode.EXAMPLE, read(answerCitedWorkedExamples)),
                Map.entry(AnswerPromptMode.TIMING, read(answerCitedTimingOrder)),
                Map.entry(AnswerPromptMode.TIE, read(answerCitedTieResolution)),
                Map.entry(AnswerPromptMode.SOURCE, read(answerDirectSourceEvidence)));
        this.answerUser = read(answerUser);
        this.answerRetrievalRewriteSystem = read(answerRetrievalRewriteSystem);
        this.answerRetrievalRewriteUser = read(answerRetrievalRewriteUser);
        this.criticSystem = combined(
                criticSystem,
                criticFidelity,
                criticAnswerScope,
                criticLessonStructure,
                criticActorAndExampleFidelity,
                criticGrammaticalRelationFidelity,
                criticQuantityRoleFidelity,
                criticRuleDependencyFidelity,
                criticPlayerReadableDependency,
                criticPredicateOwnerFidelity,
                criticSelfContainedWhyVerdict,
                criticRuleConflictCheck,
                criticSelfContainedConflictVerdict,
                criticDirectSourceEvidence,
                criticSourceScopeFidelity,
                criticSourceTermNumberFidelity,
                criticSourceCausalDirection,
                criticSourceTemporalBoundary,
                criticPermissionRuling);
        this.lessonStructureCriticSystem = read(criticLessonStructure);
        this.atomicCriticSystem = read(atomicCriticSystem);
        this.objectiveCoverageCriticSystem = read(objectiveCoverageCriticSystem);
        this.criticUser = read(criticUser);
        this.structuredOutputRepair = read(structuredOutputRepair);
        this.lessonLocalizationSystem = read(lessonLocalizationSystem);
        this.lessonLocalizationUser = read(lessonLocalizationUser);
    }

    public String teachingSystem() {
        return teachingSystem;
    }

    /** Runtime teaching prompt: one compact contract instead of every historical prompt revision. */
    public String teachingRuntimeSystem() {
        return focusedTeachingSystem;
    }

    public String teachingUser() {
        return teachingUser;
    }

    public String teachingOutlineSystem() {
        return teachingOutlineSystem;
    }

    public String teachingOutlineUser() {
        return teachingOutlineUser;
    }

    public String answerSystem() {
        return answerSystem;
    }

    /**
     * Runtime answer prompt: stable evidence rules plus at most one user-facing explanation aid.
     * The full historical prompt remains available to compatibility tests, but is not sent on every answer.
     */
    public String answerSystem(String question, String learningIntent) {
        String module = focusedAnswerModules.get(selectAnswerMode(question, learningIntent));
        return module == null || module.isBlank()
                ? focusedAnswerCoreSystem
                : focusedAnswerCoreSystem + "\n\n" + module;
    }

    public String answerUser() {
        return answerUser;
    }

    public String answerRetrievalRewriteSystem() {
        return answerRetrievalRewriteSystem;
    }

    public String answerRetrievalRewriteUser() {
        return answerRetrievalRewriteUser;
    }

    public String criticSystem() {
        return criticSystem;
    }

    public String lessonStructureCriticSystem() {
        return lessonStructureCriticSystem;
    }

    public String atomicCriticSystem() {
        return atomicCriticSystem;
    }

    public String objectiveCoverageCriticSystem() {
        return objectiveCoverageCriticSystem;
    }

    public String criticUser() {
        return criticUser;
    }

    public String structuredOutputRepair() {
        return structuredOutputRepair;
    }

    public String lessonLocalizationSystem() {
        return lessonLocalizationSystem;
    }

    public String lessonLocalizationUser() {
        return lessonLocalizationUser;
    }

    private static String read(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("agent prompt resource must not be blank");
        }
        return content;
    }

    private static String combined(Resource base, Resource... revisions) throws IOException {
        StringBuilder combined = new StringBuilder(read(base));
        for (Resource revision : revisions) {
            combined.append("\n\n").append(read(revision));
        }
        return combined.toString();
    }

    private AnswerPromptMode selectAnswerMode(String question, String learningIntent) {
        String intent = learningIntent == null ? "" : learningIntent.toUpperCase(Locale.ROOT);
        if ("EXAMPLE".equals(intent)) return AnswerPromptMode.EXAMPLE;
        if ("SOURCE".equals(intent)) return AnswerPromptMode.SOURCE;
        if ("EXCEPTIONS".equals(intent)) return AnswerPromptMode.EXCEPTIONS;
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(value, "tie", "tiebreak", "平局", "并列")) return AnswerPromptMode.TIE;
        if (containsAny(value, "difference", "compare", "versus", " vs ", "区别", "不同", "对比")) {
            return AnswerPromptMode.DEFINITIONS;
        }
        if (containsAny(value, "conflict", "override", "priority", "precedence", "contradict", "冲突", "覆盖", "优先", "矛盾")) {
            return AnswerPromptMode.RELATIONSHIP;
        }
        if (containsAny(value, "exception", "unless", "except", "restriction", "例外", "除非", "限制")) {
            return AnswerPromptMode.EXCEPTIONS;
        }
        if (containsAny(value, "all options", "all ways", "complete list", "which ways", "哪些", "所有方式", "完整列表", "列出")) {
            return AnswerPromptMode.OPTIONS;
        }
        if (containsAny(value, "simultaneous", "in what order", "resolve first", "同时", "先后顺序", "先结算")) {
            return AnswerPromptMode.TIMING;
        }
        if (containsAny(value, "calculate", "how many", "total", "算一下", "多少", "合计")) {
            return AnswerPromptMode.CALCULATION;
        }
        if (containsAny(value, "step by step", "walk me through", "what happens next", "分步", "一步一步", "流程")) {
            return AnswerPromptMode.WALKTHROUGH;
        }
        if (containsAny(value, "if ", "otherwise", "depending on", "如果", "否则", "取决于")) {
            return AnswerPromptMode.DECISION_TABLE;
        }
        if (containsAny(value, "rulebook say", "exact wording", "which page", "source", "原文", "哪一页", "出处")) {
            return AnswerPromptMode.SOURCE;
        }
        if (containsAny(value, "icon", "symbol", "diagram", "image", "图标", "符号", "图示", "图片")) {
            return AnswerPromptMode.VISUAL;
        }
        return AnswerPromptMode.NONE;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private enum AnswerPromptMode {
        NONE,
        OPTIONS,
        VISUAL,
        CALCULATION,
        RELATIONSHIP,
        WALKTHROUGH,
        DECISION_TABLE,
        EXCEPTIONS,
        DEFINITIONS,
        EXAMPLE,
        TIMING,
        TIE,
        SOURCE
    }
}
