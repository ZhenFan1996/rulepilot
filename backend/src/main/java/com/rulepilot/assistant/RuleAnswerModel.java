package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RuleAnswerModel {

    default String providerId() {
        return "unspecified";
    }

    ModelDraft compose(ModelRequest request);

    default ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
        return compose(request);
    }

    /**
     * Produces bounded search phrases only. The phrases are untrusted retrieval input, never rule evidence or an
     * answer, and may be ignored when the configured model cannot safely provide them.
     */
    default List<String> rewriteRetrievalQueries(RetrievalQueryRequest request) {
        return List.of();
    }

    /**
     * Selects a bounded semantic interpretation from application-defined choices. The result is untrusted dialogue
     * control data, never rule evidence; unsupported models preserve deterministic question understanding.
     */
    default Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
        return Optional.empty();
    }

    default boolean supportsQuestionInterpretation() {
        return false;
    }

    record QuestionInterpretationRequest(
            String question,
            String previousQuestion,
            String priorGroundedQuestion,
            String priorGroundedVerdict,
            QuestionType deterministicType,
            Set<MissingQuestionContext> deterministicMissingContext,
            LearningIntent explicitLearningIntent,
            PlayerLocale outputLanguage) {
        public QuestionInterpretationRequest {
            if (question == null || question.isBlank() || question.length() > 800
                    || deterministicType == null || deterministicMissingContext == null) {
                throw new IllegalArgumentException("question interpretation request is invalid");
            }
            question = question.strip();
            previousQuestion = optionalContext(previousQuestion);
            priorGroundedQuestion = optionalContext(priorGroundedQuestion);
            priorGroundedVerdict = optionalContext(priorGroundedVerdict);
            deterministicMissingContext = Set.copyOf(deterministicMissingContext);
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        }

        public QuestionInterpretationRequest(
                String question,
                String previousQuestion,
                String priorGroundedQuestion,
                String priorGroundedVerdict,
                QuestionType deterministicType,
                Set<MissingQuestionContext> deterministicMissingContext,
                PlayerLocale outputLanguage) {
            this(
                    question,
                    previousQuestion,
                    priorGroundedQuestion,
                    priorGroundedVerdict,
                    deterministicType,
                    deterministicMissingContext,
                    null,
                    outputLanguage);
        }

        public String explicitLearningIntentForPrompt() {
            return explicitLearningIntent == null ? "GENERAL_QUESTION" : explicitLearningIntent.name();
        }

        private static String optionalContext(String value) {
            if (value == null || value.isBlank()) return "";
            String normalized = value.strip();
            return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
        }
    }

    enum ReferenceBinding {
        CURRENT_QUESTION,
        PREVIOUS_QUESTION,
        PRIOR_GROUNDED_TURN,
        NEEDS_CLARIFICATION
    }

    enum EvidenceNeed {
        DIRECT_RULE,
        CONDITION,
        SEQUENCE,
        EXCEPTION,
        DEFINITION,
        RELATIONSHIP,
        VISUAL_REFERENCE,
        COMPLETE_LIST,
        PRIOR_TURN
    }

    record PlannedSubquestion(String questionSpan, Set<EvidenceNeed> evidenceNeeds) {
        public PlannedSubquestion {
            if (questionSpan == null || questionSpan.isBlank() || questionSpan.length() > 300
                    || evidenceNeeds == null || evidenceNeeds.isEmpty() || evidenceNeeds.size() > 3) {
                throw new IllegalArgumentException("planned answer subquestion is invalid");
            }
            questionSpan = questionSpan.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
        }
    }

    record QuestionInterpretationDraft(
            QuestionType questionType,
            ReferenceBinding referenceBinding,
            List<String> terms,
            Set<MissingQuestionContext> missingContext,
            LearningIntent learningIntent,
            List<PlannedSubquestion> subquestions) {
        public QuestionInterpretationDraft {
            if (questionType == null || referenceBinding == null || terms == null || terms.size() > 12
                    || missingContext == null || missingContext.size() > 2
                    || subquestions == null || subquestions.size() > 4) {
                throw new IllegalArgumentException("question interpretation draft is invalid");
            }
            if (terms.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 80)) {
                throw new IllegalArgumentException("question interpretation term is invalid");
            }
            terms = terms.stream()
                    .map(String::strip)
                    .distinct()
                    .toList();
            missingContext = Set.copyOf(missingContext);
            subquestions = List.copyOf(subquestions);
        }

        public QuestionInterpretationDraft(
                QuestionType questionType,
                ReferenceBinding referenceBinding,
                List<String> terms,
                Set<MissingQuestionContext> missingContext,
                List<PlannedSubquestion> subquestions) {
            this(questionType, referenceBinding, terms, missingContext, null, subquestions);
        }
    }

    record ModelRequest(String question, QuestionType questionType, AnswerContext context, List<EvidenceInput> evidence) {
        public ModelRequest {
            if (question == null || question.isBlank() || questionType == null || context == null
                    || evidence == null || evidence.isEmpty()) {
                throw new IllegalArgumentException("answer model request is invalid");
            }
            evidence = List.copyOf(evidence);
        }
    }

    record RetrievalQueryRequest(String question, String previousQuestion) {
        public RetrievalQueryRequest {
            if (question == null || question.isBlank() || question.length() > 800) {
                throw new IllegalArgumentException("retrieval query request is invalid");
            }
            question = question.strip();
            previousQuestion = optional(previousQuestion);
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value.strip();
        }
    }

    record AnswerContext(
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage) {

        public AnswerContext {
            previousQuestion = optional(previousQuestion);
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value.strip();
        }

        public String learningIntentForPrompt() {
            return learningIntent == null ? "GENERAL_QUESTION" : learningIntent.name();
        }

        public String outputLanguageForPrompt() {
            return outputLanguage.promptName();
        }
    }

    record EvidenceInput(UUID chunkId, String sectionType, String heading, String excerpt, int pageFrom, int pageTo) {}

    /** Untrusted request for application-controlled arithmetic; the application recomputes every result. */
    record CalculationRequest(String expression) {}

    /** Untrusted comparison between one cited requirement and one fact explicitly stated in the current question. */
    record SituationCheckRequest(
            String requirement,
            String status,
            String playerFact,
            List<UUID> citationIds) {}

    /** Untrusted proposed explanation step; the application validates its evidence scope and ordering label. */
    record WalkthroughStepRequest(
            String instruction,
            String explanation,
            String orderBasis,
            List<UUID> citationIds) {}

    /** Untrusted proposed condition/outcome branch tied to explicit rulebook evidence. */
    record DecisionBranchRequest(
            String condition,
            String outcome,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted proposed exception or restriction with its own directly supporting evidence. */
    record ExceptionClauseRequest(
            String condition,
            String effect,
            List<UUID> citationIds) {}

    /** Untrusted proposed rulebook term definition with an optional player-useful boundary. */
    record TermDefinitionRequest(
            String term,
            String definition,
            String boundary,
            List<UUID> citationIds) {}

    /** Untrusted worked example whose setup, action, and outcome must remain tied to cited evidence. */
    record WorkedExampleRequest(
            String setup,
            String action,
            String outcome,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted comparison of two rules whose priority must be explicitly supported by cited evidence. */
    record RulePriorityRequest(
            String baseRule,
            String competingRule,
            String resolution,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted simultaneous-effect ordering whose source must be explicit in cited evidence. */
    record RuleTimingRequest(
            String timingContext,
            String resolutionOrder,
            String orderSource,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted ordered tie ruling whose steps and terminal outcome must all be explicit in cited evidence. */
    record RuleTieRequest(
            String tieContext,
            List<String> resolutionSteps,
            String finalOutcome,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted applicability ruling matched against scope facts stated in the current question. */
    record RuleScopeRequest(
            String ruleContext,
            String governingCondition,
            String currentSituation,
            String matchStatus,
            String effect,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted side-by-side distinction between two rulebook concepts named by the player. */
    record RuleConceptComparisonRequest(
            String leftConcept,
            String leftDefinition,
            String rightConcept,
            String rightDefinition,
            String commonGround,
            String keyDifference,
            String practicalBoundary,
            String basis,
            List<UUID> citationIds) {}

    /** Untrusted member of a claimed complete, cited option set requested by the player. */
    record RuleOptionRequest(
            String decisionContext,
            String selectionRule,
            String optionName,
            String availabilityCondition,
            String result,
            String basis,
            List<UUID> citationIds) {}

    record ModelDraft(
            boolean answerable,
            String insufficiencyReason,
            String shortVerdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            String confidence,
            String answerBasis,
            List<CalculationRequest> calculations,
            List<SituationCheckRequest> situationChecks,
            List<WalkthroughStepRequest> walkthroughSteps,
            List<DecisionBranchRequest> decisionBranches,
            List<ExceptionClauseRequest> exceptionClauses,
            List<TermDefinitionRequest> termDefinitions,
            List<WorkedExampleRequest> workedExamples,
            List<RulePriorityRequest> priorityResolutions,
            List<RuleTimingRequest> timingResolutions,
            List<RuleTieRequest> tieResolutions,
            List<RuleScopeRequest> scopeResolutions,
            List<RuleConceptComparisonRequest> conceptComparisons,
            List<RuleOptionRequest> ruleOptions) {

        public ModelDraft {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
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
            answerBasis = answerable && (answerBasis == null || answerBasis.isBlank())
                    ? "DIRECT_RULE"
                    : answerBasis;
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples,
                List<RulePriorityRequest> priorityResolutions,
                List<RuleTimingRequest> timingResolutions,
                List<RuleTieRequest> tieResolutions,
                List<RuleScopeRequest> scopeResolutions,
                List<RuleConceptComparisonRequest> conceptComparisons) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions,
                    scopeResolutions, conceptComparisons, List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples,
                List<RulePriorityRequest> priorityResolutions,
                List<RuleTimingRequest> timingResolutions,
                List<RuleTieRequest> tieResolutions,
                List<RuleScopeRequest> scopeResolutions) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions,
                    scopeResolutions, List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, List.of(), List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples,
                List<RulePriorityRequest> priorityResolutions) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples,
                List<RulePriorityRequest> priorityResolutions,
                List<RuleTimingRequest> timingResolutions) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions,
                List<WorkedExampleRequest> workedExamples,
                List<RulePriorityRequest> priorityResolutions,
                List<RuleTimingRequest> timingResolutions,
                List<RuleTieRequest> tieResolutions) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, workedExamples, priorityResolutions, timingResolutions, tieResolutions, List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis) {
            this(
                    answerable,
                    insufficiencyReason,
                    shortVerdict,
                    explanation,
                    citationIds,
                    exceptions,
                    confidence,
                    answerBasis,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks) {
            this(
                    answerable,
                    insufficiencyReason,
                    shortVerdict,
                    explanation,
                    citationIds,
                    exceptions,
                    confidence,
                    answerBasis,
                    calculations,
                    situationChecks,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations) {
            this(
                    answerable,
                    insufficiencyReason,
                    shortVerdict,
                    explanation,
                    citationIds,
                    exceptions,
                    confidence,
                    answerBasis,
                    calculations,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public ModelDraft(
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence) {
            this(true, null, shortVerdict, explanation, citationIds, exceptions, confidence, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, List.of(), List.of(), List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses,
                List<TermDefinitionRequest> termDefinitions) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    termDefinitions, List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, List.of(), List.of());
        }

        public ModelDraft(
                boolean answerable,
                String insufficiencyReason,
                String shortVerdict,
                String explanation,
                List<UUID> citationIds,
                List<String> exceptions,
                String confidence,
                String answerBasis,
                List<CalculationRequest> calculations,
                List<SituationCheckRequest> situationChecks,
                List<WalkthroughStepRequest> walkthroughSteps,
                List<DecisionBranchRequest> decisionBranches,
                List<ExceptionClauseRequest> exceptionClauses) {
            this(answerable, insufficiencyReason, shortVerdict, explanation, citationIds, exceptions, confidence,
                    answerBasis, calculations, situationChecks, walkthroughSteps, decisionBranches, exceptionClauses,
                    List.of());
        }
    }
}
