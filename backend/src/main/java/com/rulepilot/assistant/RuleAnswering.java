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
            if (requirement == null || requirement.isBlank() || requirement.length() > 240
                    || !Set.of("CONFIRMED", "CONTRADICTED", "NOT_PROVIDED").contains(status)
                    || playerFact == null || playerFact.length() > 240
                    || ("NOT_PROVIDED".equals(status) != playerFact.isBlank())) {
                throw new IllegalArgumentException("public rule situation check is invalid");
            }
            requirement = requirement.strip();
            playerFact = playerFact.strip();
        }
    }

    record WalkthroughStep(String instruction, String explanation, String orderBasis) {
        public WalkthroughStep {
            if (instruction == null || instruction.isBlank() || instruction.length() > 240
                    || explanation == null || explanation.isBlank() || explanation.length() > 500
                    || !Set.of("RULE_ORDER", "EXPLANATION_ORDER").contains(orderBasis)) {
                throw new IllegalArgumentException("public rule walkthrough step is invalid");
            }
            instruction = instruction.strip();
            explanation = explanation.strip();
        }
    }

    record DecisionBranch(String condition, String outcome, String basis) {
        public DecisionBranch {
            if (condition == null || condition.isBlank() || condition.length() > 300
                    || outcome == null || outcome.isBlank() || outcome.length() > 500
                    || !Set.of("EXPLICIT_RULE", "RULEBOOK_EXAMPLE").contains(basis)) {
                throw new IllegalArgumentException("public rule decision branch is invalid");
            }
            condition = condition.strip();
            outcome = outcome.strip();
        }
    }

    record ExceptionClause(String condition, String effect) {
        public ExceptionClause {
            if (condition == null || condition.isBlank() || condition.length() > 300
                    || effect == null || effect.isBlank() || effect.length() > 500) {
                throw new IllegalArgumentException("public rule exception clause is invalid");
            }
            condition = condition.strip();
            effect = effect.strip();
        }
    }

    record TermDefinition(String term, String definition, String boundary) {
        public TermDefinition {
            if (term == null || term.isBlank() || term.length() > 120
                    || definition == null || definition.isBlank() || definition.length() > 600
                    || boundary == null || boundary.length() > 400) {
                throw new IllegalArgumentException("public rule term definition is invalid");
            }
            term = term.strip();
            definition = definition.strip();
            boundary = boundary.strip();
        }
    }

    record WorkedExample(String setup, String action, String outcome, String basis) {
        public WorkedExample {
            if (setup == null || setup.isBlank() || setup.length() > 500
                    || action == null || action.isBlank() || action.length() > 700
                    || outcome == null || outcome.isBlank() || outcome.length() > 500
                    || !Set.of("RULEBOOK_EXAMPLE", "EVIDENCE_BOUND_ILLUSTRATION").contains(basis)) {
                throw new IllegalArgumentException("public rule worked example is invalid");
            }
            setup = setup.strip();
            action = action.strip();
            outcome = outcome.strip();
        }
    }

    record RulePriorityResolution(String baseRule, String competingRule, String resolution, String basis) {
        public RulePriorityResolution {
            if (baseRule == null || baseRule.isBlank() || baseRule.length() > 500
                    || competingRule == null || competingRule.isBlank() || competingRule.length() > 500
                    || resolution == null || resolution.isBlank() || resolution.length() > 600
                    || !Set.of("EXPLICIT_OVERRIDE", "IMPOSSIBILITY_PRIORITY", "CONFLICT_ONLY_OVERRIDE")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule priority resolution is invalid");
            }
            baseRule = baseRule.strip();
            competingRule = competingRule.strip();
            resolution = resolution.strip();
        }
    }

    record RuleTimingResolution(String timingContext, String resolutionOrder, String orderSource, String basis) {
        public RuleTimingResolution {
            if (timingContext == null || timingContext.isBlank() || timingContext.length() > 500
                    || resolutionOrder == null || resolutionOrder.isBlank() || resolutionOrder.length() > 700
                    || orderSource == null || orderSource.isBlank() || orderSource.length() > 400
                    || !Set.of("CURRENT_PLAYER_CHOOSES", "PRINTED_TOP_TO_BOTTOM", "NORMAL_TURN_ORDER")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule timing resolution is invalid");
            }
            timingContext = timingContext.strip();
            resolutionOrder = resolutionOrder.strip();
            orderSource = orderSource.strip();
        }
    }

    record RuleTieResolution(
            String tieContext,
            List<String> resolutionSteps,
            String finalOutcome,
            String basis) {
        public RuleTieResolution {
            if (tieContext == null || tieContext.isBlank() || tieContext.length() > 500
                    || resolutionSteps == null || resolutionSteps.isEmpty() || resolutionSteps.size() > 6
                    || resolutionSteps.stream().anyMatch(step -> step == null || step.isBlank()
                            || step.length() > 500 || step.contains("\n") || step.contains("\r"))
                    || finalOutcome == null || finalOutcome.isBlank() || finalOutcome.length() > 500
                    || !Set.of("SINGLE_TIEBREAKER", "ORDERED_TIEBREAKERS", "RANK_REWARD_SHIFT", "POSITIONAL_PRIORITY")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule tie resolution is invalid");
            }
            tieContext = tieContext.strip();
            resolutionSteps = resolutionSteps.stream().map(String::strip).toList();
            finalOutcome = finalOutcome.strip();
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
            if (ruleContext == null || ruleContext.isBlank() || ruleContext.length() > 500
                    || governingCondition == null || governingCondition.isBlank() || governingCondition.length() > 500
                    || currentSituation == null || currentSituation.isBlank() || currentSituation.length() > 300
                    || !Set.of("MATCHES_SCOPE", "OUTSIDE_SCOPE", "NEEDS_CONTEXT").contains(matchStatus)
                    || effect == null || effect.isBlank() || effect.length() > 600
                    || !Set.of("PLAYER_COUNT", "ROLE_PRESENCE", "GAME_MODE", "VARIANT_SELECTION", "PLAYER_COUNT_EXCEPTION")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule scope resolution is invalid");
            }
            ruleContext = ruleContext.strip();
            governingCondition = governingCondition.strip();
            currentSituation = currentSituation.strip();
            effect = effect.strip();
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
            if (leftConcept == null || leftConcept.isBlank() || leftConcept.length() > 120
                    || leftDefinition == null || leftDefinition.isBlank() || leftDefinition.length() > 600
                    || rightConcept == null || rightConcept.isBlank() || rightConcept.length() > 120
                    || rightDefinition == null || rightDefinition.isBlank() || rightDefinition.length() > 600
                    || commonGround == null || commonGround.isBlank() || commonGround.length() > 500
                    || keyDifference == null || keyDifference.isBlank() || keyDifference.length() > 700
                    || practicalBoundary == null || practicalBoundary.isBlank() || practicalBoundary.length() > 600
                    || !Set.of("ACTION_WINDOW", "RESOURCE_FUNCTION", "STORAGE_STATUS", "RULE_SCOPE", "DEFINITION_BOUNDARY")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule concept comparison is invalid");
            }
            leftConcept = leftConcept.strip();
            leftDefinition = leftDefinition.strip();
            rightConcept = rightConcept.strip();
            rightDefinition = rightDefinition.strip();
            commonGround = commonGround.strip();
            keyDifference = keyDifference.strip();
            practicalBoundary = practicalBoundary.strip();
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
            if (decisionContext == null || decisionContext.isBlank() || decisionContext.length() > 240
                    || selectionRule == null || selectionRule.isBlank() || selectionRule.length() > 400
                    || optionName == null || optionName.isBlank() || optionName.length() > 160
                    || availabilityCondition == null || availabilityCondition.isBlank()
                    || availabilityCondition.length() > 500
                    || result == null || result.isBlank() || result.length() > 700
                    || !Set.of("SOURCE_SELECTION", "TIMING_CATALOG", "ALTERNATIVE_ACTION", "EXCLUSIVE_CHOICE")
                            .contains(basis)) {
                throw new IllegalArgumentException("public rule option is invalid");
            }
            decisionContext = decisionContext.strip();
            selectionRule = selectionRule.strip();
            optionName = optionName.strip();
            availabilityCondition = availabilityCondition.strip();
            result = result.strip();
        }
    }
}
