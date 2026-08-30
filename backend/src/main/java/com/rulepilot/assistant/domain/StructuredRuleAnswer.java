package com.rulepilot.assistant.domain;

import java.util.List;
import java.util.UUID;

public record StructuredRuleAnswer(
        UUID documentVersionId,
        AnswerStatus status,
        String shortVerdict,
        String explanation,
        List<RuleCitation> citations,
        List<String> exceptions,
        AnswerConfidence confidence,
        AnswerBasis answerBasis,
        boolean official,
        UUID confirmedRulingId,
        Long confirmedRulingVersion,
        String clarification,
        List<AnswerWarning> warnings,
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

    public StructuredRuleAnswer {
        if (documentVersionId == null || status == null || shortVerdict == null || citations == null
                || exceptions == null || confidence == null) {
            throw new IllegalArgumentException("structured rule answer is invalid");
        }
        citations = List.copyOf(citations);
        exceptions = List.copyOf(exceptions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        calculations = calculations == null ? List.of() : List.copyOf(calculations);
        situationChecks = situationChecks == null ? List.of() : List.copyOf(situationChecks);
        walkthroughSteps = walkthroughSteps == null ? List.of() : List.copyOf(walkthroughSteps);
        decisionBranches = decisionBranches == null ? List.of() : List.copyOf(decisionBranches);
        exceptionClauses = exceptionClauses == null ? List.of() : List.copyOf(exceptionClauses);
        termDefinitions = termDefinitions == null ? List.of() : List.copyOf(termDefinitions);
        workedExamples = workedExamples == null ? List.of() : List.copyOf(workedExamples);
        priorityResolutions = priorityResolutions == null ? List.of() : List.copyOf(priorityResolutions);
        timingResolutions = timingResolutions == null ? List.of() : List.copyOf(timingResolutions);
        tieResolutions = tieResolutions == null ? List.of() : List.copyOf(tieResolutions);
        scopeResolutions = scopeResolutions == null ? List.of() : List.copyOf(scopeResolutions);
        conceptComparisons = conceptComparisons == null ? List.of() : List.copyOf(conceptComparisons);
        ruleOptions = ruleOptions == null ? List.of() : List.copyOf(ruleOptions);
        if (status.publishesConclusion() && citations.isEmpty() && answerBasis != null) {
            throw new IllegalArgumentException("an uncited conversational response cannot claim a rule basis");
        }
        answerBasis = status.publishesConclusion() && !citations.isEmpty()
                ? answerBasis == null ? AnswerBasis.DIRECT_RULE : answerBasis
                : null;
        if (!status.publishesConclusion() && status != AnswerStatus.INSUFFICIENT_EVIDENCE && !citations.isEmpty()) {
            throw new IllegalArgumentException("only an evidence-insufficient response may expose sources");
        }
        if (!calculations.isEmpty()
                && (!status.publishesConclusion() || answerBasis != AnswerBasis.GROUNDED_APPLICATION)) {
            throw new IllegalArgumentException("rule calculations require a grounded application answer");
        }
        if (!situationChecks.isEmpty()
                && (!status.publishesConclusion() || answerBasis != AnswerBasis.GROUNDED_APPLICATION)) {
            throw new IllegalArgumentException("rule situation checks require a grounded application answer");
        }
        if (!walkthroughSteps.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule walkthrough steps require a published answer");
        }
        if (!decisionBranches.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule decision branches require a published answer");
        }
        if (!exceptionClauses.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule exception clauses require a published answer");
        }
        if (!termDefinitions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule term definitions require a published answer");
        }
        if (!workedExamples.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule worked examples require a published answer");
        }
        if (!priorityResolutions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule priority resolutions require a published answer");
        }
        if (!timingResolutions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule timing resolutions require a published answer");
        }
        if (!tieResolutions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule tie resolutions require a published answer");
        }
        if (!scopeResolutions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule scope resolutions require a published answer");
        }
        if (!conceptComparisons.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule concept comparisons require a published answer");
        }
        if (!ruleOptions.isEmpty() && !status.publishesConclusion()) {
            throw new IllegalArgumentException("rule options require a published answer");
        }
        if ((status == AnswerStatus.ANSWERED_WITH_WARNING) != !warnings.isEmpty()) {
            throw new IllegalArgumentException("answer warning status and warnings must agree");
        }
        if ((confirmedRulingId == null) != (confirmedRulingVersion == null)
                || confirmedRulingVersion != null && confirmedRulingVersion < 0
                || !status.publishesConclusion() && confirmedRulingId != null) {
            throw new IllegalArgumentException("confirmed ruling answer identity is invalid");
        }
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
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
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, priorityResolutions, timingResolutions, tieResolutions, scopeResolutions,
                conceptComparisons, List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
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
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, priorityResolutions, timingResolutions, tieResolutions, scopeResolutions,
                List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, priorityResolutions, List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, priorityResolutions, timingResolutions, List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
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
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, priorityResolutions, timingResolutions, tieResolutions, List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions,
            List<RuleWorkedExample> workedExamples) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions,
                workedExamples, List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses,
            List<RuleTermDefinition> termDefinitions) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, termDefinitions, List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings) {
        this(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                answerBasis,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                warnings,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations) {
        this(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                answerBasis,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                warnings,
                calculations,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks) {
        this(
                documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, List.of(), List.of(), List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, List.of(), List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification,
            List<AnswerWarning> warnings,
            List<RuleCalculation> calculations,
            List<RuleSituationCheck> situationChecks,
            List<RuleWalkthroughStep> walkthroughSteps,
            List<RuleDecisionBranch> decisionBranches,
            List<RuleExceptionClause> exceptionClauses) {
        this(documentVersionId, status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis,
                official, confirmedRulingId, confirmedRulingVersion, clarification, warnings, calculations,
                situationChecks, walkthroughSteps, decisionBranches, exceptionClauses, List.of(), List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification) {
        this(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                answerBasis,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                List.of());
    }

    public StructuredRuleAnswer(
            UUID documentVersionId,
            AnswerStatus status,
            String shortVerdict,
            String explanation,
            List<RuleCitation> citations,
            List<String> exceptions,
            AnswerConfidence confidence,
            boolean official,
            UUID confirmedRulingId,
            Long confirmedRulingVersion,
            String clarification) {
        this(
                documentVersionId,
                status,
                shortVerdict,
                explanation,
                citations,
                exceptions,
                confidence,
                null,
                official,
                confirmedRulingId,
                confirmedRulingVersion,
                clarification,
                List.of());
    }
}
