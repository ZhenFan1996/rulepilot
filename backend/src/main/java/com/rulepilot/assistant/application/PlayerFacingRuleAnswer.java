package com.rulepilot.assistant.application;

import com.rulepilot.assistant.RuleAnswering;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.AnswerStatus;
import com.rulepilot.assistant.domain.AnswerWarning;
import java.util.List;

/** Public answer content. Operational UUIDs and audit data deliberately live outside this record. */
public record PlayerFacingRuleAnswer(
        String language,
        AnswerStatus status,
        String shortVerdict,
        String explanation,
        List<Citation> citations,
        List<String> exceptions,
        AnswerConfidence confidence,
        AnswerBasis answerBasis,
        SourceKind source,
        String clarification,
        Recovery recovery,
        List<AnswerWarning> warnings,
        List<RuleAnswering.Calculation> calculations,
        List<RuleAnswering.SituationCheck> situationChecks,
        List<RuleAnswering.WalkthroughStep> walkthroughSteps,
        List<RuleAnswering.DecisionBranch> decisionBranches,
        List<RuleAnswering.ExceptionClause> exceptionClauses,
        List<RuleAnswering.TermDefinition> termDefinitions,
        List<RuleAnswering.WorkedExample> workedExamples,
        List<RuleAnswering.RulePriorityResolution> priorityResolutions,
        List<RuleAnswering.RuleTimingResolution> timingResolutions,
        List<RuleAnswering.RuleTieResolution> tieResolutions,
        List<RuleAnswering.RuleScopeResolution> scopeResolutions,
        List<RuleAnswering.RuleConceptComparison> conceptComparisons,
        List<RuleAnswering.RuleOption> ruleOptions) {

    public PlayerFacingRuleAnswer {
        if (language == null || status == null || shortVerdict == null || explanation == null
                || citations == null || exceptions == null || confidence == null || source == null
                || warnings == null || calculations == null || situationChecks == null || walkthroughSteps == null
                || decisionBranches == null || exceptionClauses == null || termDefinitions == null
                || workedExamples == null || priorityResolutions == null || timingResolutions == null
                || tieResolutions == null || scopeResolutions == null || conceptComparisons == null
                || ruleOptions == null) {
            throw new IllegalArgumentException("player-facing answer is invalid");
        }
        if (!("zh-CN".equals(language) || "en".equals(language)) || shortVerdict.isBlank()) {
            throw new IllegalArgumentException("player-facing answer identity is invalid");
        }
        citations = List.copyOf(citations);
        exceptions = List.copyOf(exceptions);
        warnings = List.copyOf(warnings);
        calculations = List.copyOf(calculations);
        situationChecks = List.copyOf(situationChecks);
        walkthroughSteps = List.copyOf(walkthroughSteps);
        decisionBranches = List.copyOf(decisionBranches);
        exceptionClauses = List.copyOf(exceptionClauses);
        termDefinitions = List.copyOf(termDefinitions);
        workedExamples = List.copyOf(workedExamples);
        priorityResolutions = List.copyOf(priorityResolutions);
        timingResolutions = List.copyOf(timingResolutions);
        tieResolutions = List.copyOf(tieResolutions);
        scopeResolutions = List.copyOf(scopeResolutions);
        conceptComparisons = List.copyOf(conceptComparisons);
        ruleOptions = List.copyOf(ruleOptions);
        boolean publishesConclusion = status.publishesConclusion();
        boolean hasStructuredDetails = !calculations.isEmpty()
                || !situationChecks.isEmpty()
                || !walkthroughSteps.isEmpty()
                || !decisionBranches.isEmpty()
                || !exceptionClauses.isEmpty()
                || !termDefinitions.isEmpty()
                || !workedExamples.isEmpty()
                || !priorityResolutions.isEmpty()
                || !timingResolutions.isEmpty()
                || !tieResolutions.isEmpty()
                || !scopeResolutions.isEmpty()
                || !conceptComparisons.isEmpty()
                || !ruleOptions.isEmpty();
        if (publishesConclusion
                && (citations.isEmpty() || answerBasis == null || clarification != null || recovery != null
                        || (status == AnswerStatus.ANSWERED_WITH_WARNING) != !warnings.isEmpty())) {
            throw new IllegalArgumentException("player-facing conclusion is invalid");
        }
        if (!publishesConclusion
                && (recovery == null || confidence != AnswerConfidence.LOW || answerBasis != null
                        || !explanation.isEmpty() || !exceptions.isEmpty() || !warnings.isEmpty() || hasStructuredDetails
                        || status != AnswerStatus.INSUFFICIENT_EVIDENCE && !citations.isEmpty()
                        || (status == AnswerStatus.CLARIFICATION_REQUIRED) != (clarification != null))) {
            throw new IllegalArgumentException("player-facing recovery is invalid");
        }
    }

    public enum SourceKind {
        CONFIRMED,
        OFFICIAL,
        UPLOADED
    }

    public record Citation(String heading, String excerpt, int pageFrom, int pageTo) {
        public Citation {
            if (heading == null || excerpt == null || pageFrom < 1 || pageTo < pageFrom) {
                throw new IllegalArgumentException("player-facing citation is invalid");
            }
        }
    }

    /** Natural-language recovery copy; draft is placed in the editor and is never submitted automatically. */
    public record Recovery(String message, String actionLabel, String draft) {
        public Recovery {
            if (message == null || message.isBlank() || actionLabel == null || actionLabel.isBlank() || draft == null) {
                throw new IllegalArgumentException("player-facing recovery is invalid");
            }
        }
    }
}
