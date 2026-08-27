package com.rulepilot.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class VersionedAgentPrompts {

    private final String focusedTeachingSystem;
    private final String teachingUser;
    private final String teachingOutlineSystem;
    private final String teachingOutlineUser;
    private final String focusedAnswerCoreSystem;
    private final Map<String, String> focusedAnswerModules;
    private final String answerUser;
    private final String criticSystem;
    private final String lessonStructureCriticSystem;
    private final String atomicCriticSystem;
    private final String objectiveCoverageCriticSystem;
    private final String criticUser;
    private final String atomicCriticUser;
    private final String structuredOutputRepair;
    private final String criticOutputRepair;
    private final String lessonLocalizationSystem;
    private final String lessonLocalizationUser;

    public VersionedAgentPrompts(
            @Value("classpath:prompts/teaching-agent-v45-focused-source-contract-system.txt") Resource focusedTeachingSystem,
            @Value("classpath:prompts/teaching-agent-v44-quantitative-aggregation-system.txt") Resource teachingQuantitativeAggregation,
            @Value("classpath:prompts/teaching-agent-v12-user.txt") Resource teachingUser,
            @Value("classpath:prompts/teaching-outline-v14-intrinsic-scope-system.txt") Resource teachingOutlineSystem,
            @Value("classpath:prompts/teaching-outline-v7-fidelity-system.txt") Resource teachingOutlineFidelity,
            @Value("classpath:prompts/teaching-outline-v8-visual-density-system.txt") Resource teachingOutlineVisualDensity,
            @Value("classpath:prompts/teaching-outline-v9-core-evidence-system.txt") Resource teachingOutlineCoreEvidence,
            @Value("classpath:prompts/teaching-outline-v10-chapter-ownership-system.txt") Resource teachingOutlineChapterOwnership,
            @Value("classpath:prompts/teaching-outline-v11-visual-coverage-system.txt") Resource teachingOutlineVisualCoverage,
            @Value("classpath:prompts/teaching-outline-v15-retired-audience-system.txt") Resource teachingOutlineRulebookScope,
            @Value("classpath:prompts/teaching-outline-v13-player-goal-system.txt") Resource teachingOutlinePlayerGoal,
            @Value("classpath:prompts/teaching-outline-v16-quantitative-aggregation-system.txt") Resource teachingOutlineQuantitativeAggregation,
            @Value("classpath:prompts/teaching-outline-v17-source-dependency-system.txt") Resource teachingOutlineSourceDependency,
            @Value("classpath:prompts/teaching-outline-v18-source-coverage-contract-system.txt") Resource teachingOutlineSourceCoverageContract,
            @Value("classpath:prompts/teaching-outline-v6-user.txt") Resource teachingOutlineUser,
            @Value("classpath:prompts/rule-answer-agent-v19-complete-list-system.txt") Resource answerCompleteList,
            @Value("classpath:prompts/rule-answer-agent-v21-resolved-visual-language-system.txt") Resource answerResolvedVisualLanguage,
            @Value("classpath:prompts/rule-answer-agent-v22-mechanical-list-consistency-system.txt") Resource answerMechanicalListConsistency,
            @Value("classpath:prompts/rule-answer-agent-v24-grounded-calculation-system.txt") Resource answerGroundedCalculation,
            @Value("classpath:prompts/rule-answer-agent-v25-rule-relationship-fidelity-system.txt") Resource answerRuleRelationshipFidelity,
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
            @Value("classpath:prompts/rule-answer-agent-v43-cited-rule-dependency-system.txt") Resource answerCitedRuleDependency,
            @Value("classpath:prompts/rule-answer-agent-v44-player-readable-rule-dependency-system.txt") Resource answerPlayerReadableDependency,
            @Value("classpath:prompts/rule-answer-agent-v46-self-contained-why-verdict-system.txt") Resource answerSelfContainedWhyVerdict,
            @Value("classpath:prompts/rule-answer-agent-v47-cited-rule-conflict-check-system.txt") Resource answerCitedRuleConflictCheck,
            @Value("classpath:prompts/rule-answer-agent-v48-self-contained-conflict-verdict-system.txt") Resource answerSelfContainedConflictVerdict,
            @Value("classpath:prompts/rule-answer-agent-v49-direct-source-evidence-system.txt") Resource answerDirectSourceEvidence,
            @Value("classpath:prompts/rule-answer-agent-v54-permission-ruling-system.txt") Resource answerPermissionRuling,
            @Value("classpath:prompts/rule-answer-agent-v60-lean-runtime-core.txt") Resource answerLeanRuntimeCore,
            @Value("classpath:prompts/rule-answer-agent-v6-user.txt") Resource answerUser,
            @Value("classpath:prompts/content-critic-v34-focused-runtime-system.txt") Resource focusedCriticSystem,
            @Value("classpath:prompts/content-critic-v35-quantitative-aggregation-system.txt") Resource criticQuantitativeAggregation,
            @Value("classpath:prompts/content-critic-v36-claim-aspect-contract-system.txt") Resource criticClaimAspectContract,
            @Value("classpath:prompts/content-critic-v37-answer-source-authority-system.txt") Resource criticAnswerSourceAuthority,
            @Value("classpath:prompts/content-critic-v38-exact-output-contract-system.txt") Resource criticExactOutputContract,
            @Value("classpath:prompts/atomic-content-critic-v3-system.txt") Resource atomicCriticSystem,
            @Value("classpath:prompts/atomic-content-critic-v5-claim-aspect-system.txt") Resource atomicClaimAspectSystem,
            @Value("classpath:prompts/atomic-content-critic-v6-answer-source-authority-system.txt") Resource atomicAnswerSourceAuthority,
            @Value("classpath:prompts/objective-coverage-critic-v3-system.txt") Resource objectiveCoverageCriticSystem,
            @Value("classpath:prompts/content-critic-v4-user.txt") Resource criticUser,
            @Value("classpath:prompts/atomic-content-critic-v7-confirmed-only-user.txt") Resource atomicCriticUser,
            @Value("classpath:prompts/atomic-content-critic-v8-claim-aspect-confirmed-only-user.txt") Resource atomicClaimAspectUser,
            @Value("classpath:prompts/structured-output-repair-v1.txt") Resource structuredOutputRepair,
            @Value("classpath:prompts/content-critic-output-repair-v1.txt") Resource criticOutputRepair,
            @Value("classpath:prompts/content-critic-output-repair-v2-claim-aspect.txt") Resource criticClaimAspectRepair,
            @Value("classpath:prompts/lesson-localization-v2-system.txt") Resource lessonLocalizationSystem,
            @Value("classpath:prompts/lesson-localization-v1-user.txt") Resource lessonLocalizationUser)
            throws IOException {
        this.focusedTeachingSystem = combined(focusedTeachingSystem, teachingQuantitativeAggregation);
        this.teachingUser = read(teachingUser);
        this.teachingOutlineSystem = combined(
                teachingOutlineSystem,
                teachingOutlineFidelity,
                teachingOutlineVisualDensity,
                teachingOutlineCoreEvidence,
                teachingOutlineChapterOwnership,
                teachingOutlineVisualCoverage,
                teachingOutlineRulebookScope,
                teachingOutlinePlayerGoal,
                teachingOutlineQuantitativeAggregation,
                teachingOutlineSourceDependency,
                teachingOutlineSourceCoverageContract);
        this.teachingOutlineUser = read(teachingOutlineUser);
        this.focusedAnswerCoreSystem = read(answerLeanRuntimeCore);
        this.focusedAnswerModules = Map.ofEntries(
                Map.entry("NONE", ""),
                Map.entry("OPTIONS", combined(answerCompleteList, answerCitedRuleOptions)),
                Map.entry("VISUAL", combined(answerResolvedVisualLanguage, answerMechanicalListConsistency)),
                Map.entry("CALCULATION", read(answerGroundedCalculation)),
                Map.entry("RULE_PRIORITY", combined(
                        answerRuleRelationshipFidelity,
                        answerCitedRulePriority,
                        answerCitedRuleConflictCheck,
                        answerSelfContainedConflictVerdict)),
                Map.entry("WALKTHROUGH", combined(
                        answerCitedWalkthrough,
                        answerCitedRuleDependency,
                        answerPlayerReadableDependency,
                        answerSelfContainedWhyVerdict)),
                Map.entry("DECISION_TABLE", read(answerCitedDecisionTable)),
                Map.entry("EXCEPTIONS", combined(
                        answerCitedExceptionClauses,
                        answerExceptionFocus,
                        answerConciseExceptionVerdict,
                        answerSelfContainedExceptionClauses)),
                Map.entry("DEFINITIONS", read(answerCitedTermDefinitions)),
                Map.entry("CONCEPT_COMPARISON", read(answerCitedConceptComparison)),
                Map.entry("EXAMPLE", read(answerCitedWorkedExamples)),
                Map.entry("TIMING", read(answerCitedTimingOrder)),
                Map.entry("TIE", read(answerCitedTieResolution)),
                Map.entry("SCOPE", read(answerCitedRuleScope)),
                Map.entry("SOURCE", read(answerDirectSourceEvidence)),
                Map.entry("PERMISSION", read(answerPermissionRuling)));
        this.answerUser = read(answerUser);
        this.criticSystem = combined(
                focusedCriticSystem,
                criticQuantitativeAggregation,
                criticClaimAspectContract,
                criticAnswerSourceAuthority,
                criticExactOutputContract);
        this.lessonStructureCriticSystem = combined(
                focusedCriticSystem,
                criticQuantitativeAggregation,
                criticClaimAspectContract,
                criticExactOutputContract);
        this.atomicCriticSystem = combined(
                atomicCriticSystem,
                atomicClaimAspectSystem,
                atomicAnswerSourceAuthority,
                criticExactOutputContract);
        this.objectiveCoverageCriticSystem = combined(
                objectiveCoverageCriticSystem,
                criticClaimAspectContract,
                criticExactOutputContract);
        this.criticUser = read(criticUser);
        this.atomicCriticUser = combined(atomicCriticUser, atomicClaimAspectUser);
        this.structuredOutputRepair = read(structuredOutputRepair);
        this.criticOutputRepair = combined(criticOutputRepair, criticClaimAspectRepair);
        this.lessonLocalizationSystem = read(lessonLocalizationSystem);
        this.lessonLocalizationUser = read(lessonLocalizationUser);
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

    /**
     * Runtime answer prompt: stable evidence rules plus at most one user-facing explanation aid.
     */
    public String answerSystem(String answerAid) {
        String module = focusedAnswerModules.get(answerAid == null || answerAid.isBlank() ? "NONE" : answerAid);
        return module == null || module.isBlank()
                ? focusedAnswerCoreSystem
                : focusedAnswerCoreSystem + "\n\n" + module;
    }

    public String answerUser() {
        return answerUser;
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

    public String atomicCriticUser() {
        return atomicCriticUser;
    }

    public String structuredOutputRepair() {
        return structuredOutputRepair;
    }

    public String criticOutputRepair() {
        return criticOutputRepair;
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

}
