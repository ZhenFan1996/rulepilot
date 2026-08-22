package com.rulepilot.assistant.application;

import com.rulepilot.assistant.EvidenceVerifier;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceClaim;
import com.rulepilot.assistant.EvidenceVerifier.EvidenceSource;
import com.rulepilot.assistant.RuleAnswerModel.ModelDraft;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.RuleCalculation;
import com.rulepilot.assistant.domain.RuleDecisionBranch;
import com.rulepilot.assistant.domain.RuleExceptionClause;
import com.rulepilot.assistant.domain.RuleSituationCheck;
import com.rulepilot.assistant.domain.RuleTermDefinition;
import com.rulepilot.assistant.domain.RuleWalkthroughStep;
import com.rulepilot.assistant.domain.RuleWorkedExample;
import com.rulepilot.assistant.domain.RulePriorityResolution;
import com.rulepilot.assistant.domain.RuleTimingResolution;
import com.rulepilot.assistant.domain.RuleTieResolution;
import com.rulepilot.assistant.domain.RuleScopeResolution;
import com.rulepilot.assistant.domain.RuleConceptComparison;
import com.rulepilot.assistant.domain.RuleOption;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Keeps the answer-publication boundary strict after untrusted model generation. */
final class AnswerPublicationValidator {

    private final EvidenceVerifier evidenceVerifier;

    AnswerPublicationValidator(EvidenceVerifier evidenceVerifier) {
        this.evidenceVerifier = evidenceVerifier;
    }

    EvidenceVerifier.Verification verifySources(UUID versionId, List<HybridEvidenceHit> evidence) {
        return evidenceVerifier.verify(new EvidenceVerifier.VerificationRequest(
                versionId, evidence.stream().map(AnswerPublicationValidator::toVerifierEvidence).toList(), List.of()));
    }

    StructuredRuleAnswer publish(UUID versionId, ModelDraft draft, List<HybridEvidenceHit> evidence) {
        return publish(versionId, draft, evidence, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations) {
        return publish(versionId, draft, evidence, calculations, List.of(), List.of(), List.of(), List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks) {
        return publish(versionId, draft, evidence, calculations, situationChecks, List.of(), List.of(), List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, List.of(), List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, priorityResolutions, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, priorityResolutions, timingResolutions, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions,
            List<RuleTieResolution> tieResolutions) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, priorityResolutions, timingResolutions,
                tieResolutions, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions,
            List<RuleTieResolution> tieResolutions,
            List<RuleScopeResolution> scopeResolutions) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, priorityResolutions, timingResolutions,
                tieResolutions, scopeResolutions, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions,
            List<RuleTieResolution> tieResolutions,
            List<RuleScopeResolution> scopeResolutions,
            List<RuleConceptComparison> conceptComparisons) {
        return publish(versionId, draft, evidence, calculations, situationChecks, walkthroughSteps, decisionBranches,
                exceptionClauses, termDefinitions, workedExamples, priorityResolutions, timingResolutions,
                tieResolutions, scopeResolutions, conceptComparisons, List.of());
    }

    StructuredRuleAnswer publish(
            UUID versionId,
            ModelDraft draft,
            List<HybridEvidenceHit> evidence,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions,
            List<RuleTieResolution> tieResolutions,
            List<RuleScopeResolution> scopeResolutions,
            List<RuleConceptComparison> conceptComparisons,
            List<RuleOption> ruleOptions) {
        String shortVerdict = draft.shortVerdict();
        String explanation = draft.explanation();
        if (shortVerdict == null || shortVerdict.isBlank()
                || explanation == null || explanation.isBlank()
                || draft.citationIds().isEmpty()
                || draft.exceptions().stream().anyMatch(exception -> exception == null || exception.isBlank())) {
            throw new IllegalArgumentException("model draft is incomplete");
        }
        String completeAnswer = shortVerdict + "\n" + explanation + "\n"
                + String.join("\n", draft.exceptions()) + "\n"
                + calculations.stream()
                        .map(calculation -> calculation.expression() + "\n" + calculation.result())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + situationChecks.stream()
                        .map(check -> check.requirement() + "\n" + check.playerFact())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + walkthroughSteps.stream()
                        .map(step -> step.instruction() + "\n" + step.explanation())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + decisionBranches.stream()
                        .map(branch -> branch.condition() + "\n" + branch.outcome())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + exceptionClauses.stream()
                        .map(clause -> clause.condition() + "\n" + clause.effect())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + termDefinitions.stream()
                        .map(definition -> definition.term() + "\n" + definition.definition() + "\n"
                                + definition.boundary())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + workedExamples.stream()
                        .map(example -> example.setup() + "\n" + example.action() + "\n" + example.outcome())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + priorityResolutions.stream()
                        .map(resolution -> resolution.baseRule() + "\n" + resolution.competingRule() + "\n"
                                + resolution.resolution())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + timingResolutions.stream()
                        .map(resolution -> resolution.timingContext() + "\n" + resolution.resolutionOrder() + "\n"
                                + resolution.orderSource())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + tieResolutions.stream()
                        .map(resolution -> resolution.tieContext() + "\n"
                                + String.join("\n", resolution.resolutionSteps()) + "\n"
                                + resolution.finalOutcome())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + scopeResolutions.stream()
                        .map(resolution -> resolution.ruleContext() + "\n" + resolution.governingCondition() + "\n"
                                + resolution.currentSituation() + "\n" + resolution.effect())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + conceptComparisons.stream()
                        .map(comparison -> comparison.leftConcept() + "\n" + comparison.leftDefinition() + "\n"
                                + comparison.rightConcept() + "\n" + comparison.rightDefinition() + "\n"
                                + comparison.commonGround() + "\n" + comparison.keyDifference() + "\n"
                                + comparison.practicalBoundary())
                        .collect(java.util.stream.Collectors.joining("\n")) + "\n"
                + ruleOptions.stream()
                        .map(option -> option.decisionContext() + "\n" + option.selectionRule() + "\n"
                                + option.optionName() + "\n" + option.availabilityCondition() + "\n"
                                + option.result())
                        .collect(java.util.stream.Collectors.joining("\n"));
        List<EvidenceClaim> claims = new java.util.ArrayList<>();
        claims.add(new EvidenceClaim(completeAnswer, draft.citationIds()));
        situationChecks.forEach(check -> claims.add(new EvidenceClaim(check.requirement(), check.citationIds())));
        walkthroughSteps.forEach(step -> claims.add(new EvidenceClaim(
                step.instruction() + "\n" + step.explanation(), step.citationIds())));
        decisionBranches.forEach(branch -> claims.add(new EvidenceClaim(
                branch.condition() + "\n" + branch.outcome(), branch.citationIds())));
        exceptionClauses.forEach(clause -> claims.add(new EvidenceClaim(
                clause.condition() + "\n" + clause.effect(), clause.citationIds())));
        termDefinitions.forEach(definition -> claims.add(new EvidenceClaim(
                definition.term() + "\n" + definition.definition() + "\n" + definition.boundary(),
                definition.citationIds())));
        workedExamples.forEach(example -> claims.add(new EvidenceClaim(
                example.setup() + "\n" + example.action() + "\n" + example.outcome(),
                example.citationIds())));
        priorityResolutions.forEach(resolution -> claims.add(new EvidenceClaim(
                resolution.baseRule() + "\n" + resolution.competingRule() + "\n" + resolution.resolution(),
                resolution.citationIds())));
        timingResolutions.forEach(resolution -> claims.add(new EvidenceClaim(
                resolution.timingContext() + "\n" + resolution.resolutionOrder() + "\n" + resolution.orderSource(),
                resolution.citationIds())));
        tieResolutions.forEach(resolution -> claims.add(new EvidenceClaim(
                resolution.tieContext() + "\n" + String.join("\n", resolution.resolutionSteps()) + "\n"
                        + resolution.finalOutcome(),
                resolution.citationIds())));
        scopeResolutions.forEach(resolution -> claims.add(new EvidenceClaim(
                resolution.ruleContext() + "\n" + resolution.governingCondition() + "\n" + resolution.effect(),
                resolution.citationIds())));
        conceptComparisons.forEach(comparison -> claims.add(new EvidenceClaim(
                comparison.leftConcept() + "\n" + comparison.leftDefinition() + "\n"
                        + comparison.rightConcept() + "\n" + comparison.rightDefinition() + "\n"
                        + comparison.commonGround() + "\n" + comparison.keyDifference() + "\n"
                        + comparison.practicalBoundary(),
                comparison.citationIds())));
        ruleOptions.forEach(option -> claims.add(new EvidenceClaim(
                option.decisionContext() + "\n" + option.selectionRule() + "\n" + option.optionName() + "\n"
                        + option.availabilityCondition() + "\n" + option.result(),
                option.citationIds())));
        var verification = evidenceVerifier.verify(new EvidenceVerifier.VerificationRequest(
                versionId,
                evidence.stream().map(AnswerPublicationValidator::toVerifierEvidence).toList(),
                claims));
        if (!verification.verified()) {
            throw new IllegalArgumentException("answer evidence did not pass policy verification");
        }
        Map<UUID, HybridEvidenceHit> allowed = evidence.stream()
                .collect(Collectors.toUnmodifiableMap(
                        hit -> hit.evidence().chunkId(), Function.identity(), (first, duplicate) -> first));
        List<RuleCitation> citations = draft.citationIds().stream().distinct().map(id -> {
            HybridEvidenceHit hit = allowed.get(id);
            if (hit == null || !versionId.equals(hit.evidence().documentVersionId())) {
                throw new IllegalArgumentException("model cited evidence outside the allowed scope");
            }
            var source = hit.evidence();
            return new RuleCitation(
                    source.chunkId(), source.documentVersionId(), source.sectionType(), source.heading(),
                    source.playerExcerpt(), source.pageFrom(), source.pageTo());
        }).toList();
        AnswerConfidence confidence = draft.confidence();
        AnswerBasis answerBasis = draft.answerBasis();
        return new StructuredRuleAnswer(
                versionId, AnswerStatus.ANSWERED, shortVerdict, explanation, citations,
                draft.exceptions(), confidence, answerBasis, false, null, null, null, List.of(), calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions, workedExamples,
                priorityResolutions, timingResolutions, tieResolutions, scopeResolutions, conceptComparisons,
                ruleOptions);
    }

    private static EvidenceSource toVerifierEvidence(HybridEvidenceHit hit) {
        var source = hit.evidence();
        return new EvidenceSource(
                source.chunkId(), source.documentVersionId(), source.sectionType(), source.excerpt(),
                source.pageFrom(), source.pageTo());
    }
}
