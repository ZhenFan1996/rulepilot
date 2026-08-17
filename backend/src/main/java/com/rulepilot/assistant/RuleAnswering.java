package com.rulepilot.assistant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Public cross-module boundary for grounded, anonymous rulebook answers. */
public interface RuleAnswering {

    AnswerResult answerForPublicReader(
            UUID documentVersionId, String question, String previousQuestion);

    default AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage) {
        return answerForPublicReader(documentVersionId, question, previousQuestion);
    }

    default AnswerResult answerForPublicReader(
            UUID documentVersionId,
            String question,
            String previousQuestion,
            PlayerLocale outputLanguage,
            PublicLearningIntent learningIntent) {
        return answerForPublicReader(documentVersionId, question, previousQuestion, outputLanguage);
    }

    enum PublicLearningIntent {
        SIMPLIFY,
        EXAMPLE,
        DEFINE,
        WHY,
        EXCEPTIONS,
        SOURCE,
        VERIFY
    }

    /**
     * The evidence identities stay inside the backend module boundary. They let a caller attach a lesson crop only
     * when that crop supports the exact evidence cited by the answer; controllers still expose only readable pages.
     */
    record AnswerResult(UUID assistantRunId, Answer answer, Set<UUID> citedEvidenceIds) {
        public AnswerResult {
            citedEvidenceIds = citedEvidenceIds == null ? Set.of() : Set.copyOf(citedEvidenceIds);
        }

        public AnswerResult(UUID assistantRunId, Answer answer) {
            this(assistantRunId, answer, Set.of());
        }
    }

    record Answer(
            String status,
            String shortVerdict,
            String explanation,
            List<Citation> citations,
            List<String> exceptions,
            String confidence,
            String answerBasis,
            String clarification,
            List<Warning> warnings,
            List<Calculation> calculations,
            List<SituationCheck> situationChecks,
            List<WalkthroughStep> walkthroughSteps,
            List<DecisionBranch> decisionBranches,
            List<ExceptionClause> exceptionClauses,
            List<TermDefinition> termDefinitions,
            List<WorkedExample> workedExamples,
            List<RulePriorityResolution> priorityResolutions,
            List<RuleTimingResolution> timingResolutions,
            List<RuleTieResolution> tieResolutions,
            List<RuleScopeResolution> scopeResolutions,
            List<RuleConceptComparison> conceptComparisons,
            List<RuleOption> ruleOptions) {
        public Answer {
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
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples,
                List<RulePriorityResolution> priorityResolutions,
                List<RuleTimingResolution> timingResolutions,
                List<RuleTieResolution> tieResolutions,
                List<RuleScopeResolution> scopeResolutions,
                List<RuleConceptComparison> conceptComparisons) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions,
                    scopeResolutions, conceptComparisons, List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples,
                List<RulePriorityResolution> priorityResolutions,
                List<RuleTimingResolution> timingResolutions,
                List<RuleTieResolution> tieResolutions,
                List<RuleScopeResolution> scopeResolutions) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions,
                    scopeResolutions, List.of(), List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples,
                List<RulePriorityResolution> priorityResolutions) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, List.of(), List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples,
                List<RulePriorityResolution> priorityResolutions,
                List<RuleTimingResolution> timingResolutions) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples,
                List<RulePriorityResolution> priorityResolutions,
                List<RuleTimingResolution> timingResolutions,
                List<RuleTieResolution> tieResolutions) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions, List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions,
                List<WorkedExample> workedExamples) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches,
                List<ExceptionClause> exceptionClauses,
                List<TermDefinition> termDefinitions) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings) {
            this(
                    status,
                    shortVerdict,
                    explanation,
                    citations,
                    exceptions,
                    confidence,
                    answerBasis,
                    clarification,
                    warnings,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations) {
            this(
                    status,
                    shortVerdict,
                    explanation,
                    citations,
                    exceptions,
                    confidence,
                    answerBasis,
                    clarification,
                    warnings,
                    calculations,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification) {
            this(
                    status,
                    shortVerdict,
                    explanation,
                    citations,
                    exceptions,
                    confidence,
                    answerBasis,
                    clarification,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String clarification) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, null, clarification,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, List.of(), List.of(), List.of(), List.of());
        }

        public Answer(
                String status,
                String shortVerdict,
                String explanation,
                List<Citation> citations,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                String clarification,
                List<Warning> warnings,
                List<Calculation> calculations,
                List<SituationCheck> situationChecks,
                List<WalkthroughStep> walkthroughSteps,
                List<DecisionBranch> decisionBranches) {
            this(status, shortVerdict, explanation, citations, exceptions, confidence, answerBasis, clarification,
                    warnings, calculations, situationChecks, walkthroughSteps, decisionBranches, List.of(), List.of(), List.of());
        }
    }

    record Citation(String heading, int pageFrom, int pageTo) {}

    record Warning(String type) {}

    record Calculation(String expression, String result) {
        public Calculation {
            if (expression == null || expression.isBlank() || expression.length() > 160
                    || result == null || result.isBlank() || result.length() > 80) {
                throw new IllegalArgumentException("public rule calculation is invalid");
            }
            expression = expression.strip();
            result = result.strip();
        }
    }

    record SituationCheck(String requirement, String status, String playerFact) {
        public SituationCheck {
            if (requirement == null || requirement.isBlank()
                    || !Set.of("CONFIRMED", "CONTRADICTED", "NOT_PROVIDED").contains(status)
                    || playerFact == null
                    || ("NOT_PROVIDED".equals(status) != playerFact.isBlank())) {
                throw new IllegalArgumentException("public rule situation check is invalid");
            }
        }
    }

    record WalkthroughStep(String instruction, String explanation, String orderBasis) {
        public WalkthroughStep {
            if (instruction == null || instruction.isBlank()
                    || explanation == null || explanation.isBlank()
                    || !Set.of("RULE_ORDER", "EXPLANATION_ORDER").contains(orderBasis)) {
                throw new IllegalArgumentException("public rule walkthrough step is invalid");
            }
        }
    }

    record DecisionBranch(String condition, String outcome, String basis) {
        public DecisionBranch {
            if (condition == null || condition.isBlank()
                    || outcome == null || outcome.isBlank()
                    || !Set.of("EXPLICIT_RULE", "RULEBOOK_EXAMPLE").contains(basis)) {
                throw new IllegalArgumentException("public rule decision branch is invalid");
            }
        }
    }

    record ExceptionClause(String condition, String effect) {
        public ExceptionClause {
            if (condition == null || condition.isBlank()
                    || effect == null || effect.isBlank()) {
                throw new IllegalArgumentException("public rule exception clause is invalid");
            }
        }
    }

    record TermDefinition(String term, String definition, String boundary) {
        public TermDefinition {
            if (term == null || term.isBlank()
                    || definition == null || definition.isBlank()
                    || boundary == null) {
                throw new IllegalArgumentException("public rule term definition is invalid");
            }
        }
    }

    record WorkedExample(String setup, String action, String outcome, String basis) {
        public WorkedExample {
            if (setup == null || setup.isBlank()
                    || action == null || action.isBlank()
                    || outcome == null || outcome.isBlank()
                    || !Set.of("RULEBOOK_EXAMPLE", "EVIDENCE_BOUND_ILLUSTRATION").contains(basis)) {
                throw new IllegalArgumentException("public rule worked example is invalid");
            }
        }
    }

    record RulePriorityResolution(String baseRule, String competingRule, String resolution, String basis) {
        public RulePriorityResolution {
            if (baseRule == null || baseRule.isBlank()
                    || competingRule == null || competingRule.isBlank()
                    || resolution == null || resolution.isBlank()
                    || !Set.of("EXPLICIT_OVERRIDE", "IMPOSSIBILITY_PRIORITY", "CONFLICT_ONLY_OVERRIDE")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule priority resolution is invalid");
            }
        }
    }

    record RuleTimingResolution(String timingContext, String resolutionOrder, String orderSource, String basis) {
        public RuleTimingResolution {
            if (timingContext == null || timingContext.isBlank()
                    || resolutionOrder == null || resolutionOrder.isBlank()
                    || orderSource == null || orderSource.isBlank()
                    || !Set.of("CURRENT_PLAYER_CHOOSES", "PRINTED_TOP_TO_BOTTOM", "NORMAL_TURN_ORDER")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule timing resolution is invalid");
            }
        }
    }

    record RuleTieResolution(
            String tieContext,
            List<String> resolutionSteps,
            String finalOutcome,
            String basis) {
        public RuleTieResolution {
            if (tieContext == null || tieContext.isBlank()
                    || resolutionSteps == null || resolutionSteps.isEmpty()
                    || resolutionSteps.stream().anyMatch(step -> step == null || step.isBlank())
                    || finalOutcome == null || finalOutcome.isBlank()
                    || !Set.of("SINGLE_TIEBREAKER", "ORDERED_TIEBREAKERS", "RANK_REWARD_SHIFT", "POSITIONAL_PRIORITY")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule tie resolution is invalid");
            }
        }
    }

    record RuleScopeResolution(
            String ruleContext,
            String governingCondition,
            String currentSituation,
            String matchStatus,
            String effect,
            String basis) {
        public RuleScopeResolution {
            if (ruleContext == null || ruleContext.isBlank()
                    || governingCondition == null || governingCondition.isBlank()
                    || currentSituation == null || currentSituation.isBlank()
                    || !Set.of("MATCHES_SCOPE", "OUTSIDE_SCOPE", "NEEDS_CONTEXT").contains(matchStatus)
                    || effect == null || effect.isBlank()
                    || !Set.of("PLAYER_COUNT", "ROLE_PRESENCE", "GAME_MODE", "VARIANT_SELECTION", "PLAYER_COUNT_EXCEPTION")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule scope resolution is invalid");
            }
        }
    }

    record RuleConceptComparison(
            String leftConcept,
            String leftDefinition,
            String rightConcept,
            String rightDefinition,
            String commonGround,
            String keyDifference,
            String practicalBoundary,
            String basis) {
        public RuleConceptComparison {
            if (leftConcept == null || leftConcept.isBlank()
                    || leftDefinition == null || leftDefinition.isBlank()
                    || rightConcept == null || rightConcept.isBlank()
                    || rightDefinition == null || rightDefinition.isBlank()
                    || commonGround == null || commonGround.isBlank()
                    || keyDifference == null || keyDifference.isBlank()
                    || practicalBoundary == null || practicalBoundary.isBlank()
                    || !Set.of("ACTION_WINDOW", "RESOURCE_FUNCTION", "STORAGE_STATUS", "RULE_SCOPE", "DEFINITION_BOUNDARY")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule concept comparison is invalid");
            }
        }
    }

    record RuleOption(
            String decisionContext,
            String selectionRule,
            String optionName,
            String availabilityCondition,
            String result,
            String basis) {
        public RuleOption {
            if (decisionContext == null || decisionContext.isBlank()
                    || selectionRule == null || selectionRule.isBlank()
                    || optionName == null || optionName.isBlank()
                    || availabilityCondition == null || availabilityCondition.isBlank()
                    || result == null || result.isBlank()
                    || !Set.of("SOURCE_SELECTION", "TIMING_CATALOG", "ALTERNATIVE_ACTION", "EXCLUSIVE_CHOICE")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule option is invalid");
            }
        }
    }
}
