package com.rulepilot.assistant;

import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.ConceptComparisonBasis;
import com.rulepilot.assistant.domain.DecisionBranchBasis;
import com.rulepilot.assistant.domain.LearningIntent;
import com.rulepilot.assistant.domain.MissingQuestionContext;
import com.rulepilot.assistant.domain.QuestionType;
import com.rulepilot.assistant.domain.RuleOptionBasis;
import com.rulepilot.assistant.domain.RulePriorityBasis;
import com.rulepilot.assistant.domain.ScopeBasis;
import com.rulepilot.assistant.domain.ScopeMatchStatus;
import com.rulepilot.assistant.domain.TieResolutionBasis;
import com.rulepilot.assistant.domain.TimingOrderBasis;
import com.rulepilot.assistant.domain.WalkthroughOrderBasis;
import com.rulepilot.assistant.domain.WorkedExampleBasis;
import com.rulepilot.assistant.RuleAnswerModelInvalidOutputException.RejectedOutput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RuleAnswerModel {

    default String providerId() {
        return "unspecified";
    }

    default String providerId(String ownerUsername) {
        return providerId();
    }

    /**
     * Produces one complete answer envelope. Implementations distinguish provider/configuration unavailability,
     * request timeout, and a provider response that fails the structured-output contract with the matching typed
     * exception in this package.
     */
    ModelDraft compose(ModelRequest request);

    default ModelDraft compose(ModelRequest request, String ownerUsername) {
        return compose(request);
    }

    /** Produces one complete replacement after the application rejected a structured envelope. */
    default ModelDraft replaceInvalidOutput(ModelRequest request, RejectedOutput rejectedOutput) {
        return compose(request);
    }

    default ModelDraft replaceInvalidOutput(
            ModelRequest request, RejectedOutput rejectedOutput, String ownerUsername) {
        return replaceInvalidOutput(request, rejectedOutput);
    }

    /**
     * Produces one complete replacement after application validation rejected an otherwise decoded draft.
     * Production adapters must return the complete candidate, exact validation error, original schema, and allowed
     * evidence identities to the same answer model; the default keeps lightweight test models source-compatible.
     */
    default ModelDraft replaceValidationRejectedOutput(
            ModelRequest request, ModelDraft rejectedDraft, String validationError) {
        return revise(
                request,
                rejectedDraft,
                List.of(
                        "The application rejected the complete answer: " + validationError,
                        "Return one complete replacement object; do not return a patch or commentary."));
    }

    default ModelDraft replaceValidationRejectedOutput(
            ModelRequest request,
            ModelDraft rejectedDraft,
            String validationError,
            String ownerUsername) {
        return replaceValidationRejectedOutput(request, rejectedDraft, validationError);
    }

    default ModelDraft revise(ModelRequest request, ModelDraft previousDraft, List<String> feedback) {
        return compose(request);
    }

    default ModelDraft revise(
            ModelRequest request,
            ModelDraft previousDraft,
            List<String> feedback,
            String ownerUsername) {
        return revise(request, previousDraft, feedback);
    }

    /**
     * Selects a bounded semantic interpretation from application-defined choices. The result is untrusted dialogue
     * control data, never rule evidence; unsupported models preserve deterministic question understanding.
     */
    default Optional<QuestionInterpretationDraft> interpretQuestion(QuestionInterpretationRequest request) {
        return Optional.empty();
    }

    default Optional<QuestionInterpretationDraft> interpretQuestion(
            QuestionInterpretationRequest request, String ownerUsername) {
        return interpretQuestion(request);
    }

    /** Produces one complete replacement for a rejected question-interpretation envelope. */
    default Optional<QuestionInterpretationDraft> replaceInvalidQuestionInterpretation(
            QuestionInterpretationRequest request, RejectedOutput rejectedOutput) {
        return interpretQuestion(request);
    }

    default Optional<QuestionInterpretationDraft> replaceInvalidQuestionInterpretation(
            QuestionInterpretationRequest request,
            RejectedOutput rejectedOutput,
            String ownerUsername) {
        return replaceInvalidQuestionInterpretation(request, rejectedOutput);
    }

    default boolean supportsQuestionInterpretation() {
        return false;
    }

    default boolean supportsQuestionInterpretation(String ownerUsername) {
        return supportsQuestionInterpretation();
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
            if (question == null || question.isBlank()
                    || deterministicType == null || deterministicMissingContext == null) {
                throw new IllegalArgumentException("question interpretation request is invalid");
            }
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
            return value;
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
        ADVICE,
        PRIOR_TURN
    }

    /** Whether every configured retrieval source was available for this answer. */
    enum EvidenceCoverage {
        COMPLETE,
        PARTIAL
    }

    /** One player-facing shape selected by the semantic planning stage. */
    enum AnswerAid {
        NONE,
        WALKTHROUGH,
        DECISION_TABLE,
        EXCEPTIONS,
        DEFINITIONS,
        EXAMPLE,
        RULE_PRIORITY,
        TIMING,
        TIE,
        SCOPE,
        CONCEPT_COMPARISON,
        OPTIONS,
        SOURCE,
        PERMISSION,
        CALCULATION,
        VISUAL;

        public static AnswerAid forLearningIntent(LearningIntent intent) {
            if (intent == null) return NONE;
            return switch (intent) {
                case EXAMPLE -> EXAMPLE;
                case DEFINE -> DEFINITIONS;
                case WHY -> WALKTHROUGH;
                case EXCEPTIONS -> EXCEPTIONS;
                case SOURCE -> SOURCE;
                case SIMPLIFY, VERIFY -> NONE;
            };
        }
    }

    enum SubquestionOwner {
        CURRENT_QUESTION,
        BOUND_REFERENCE
    }

    record PlannedSubquestion(
            String questionSpan,
            Set<EvidenceNeed> evidenceNeeds,
            SubquestionOwner owner,
            List<String> retrievalQueries) {
        public PlannedSubquestion {
            if (questionSpan == null || questionSpan.isBlank()
                    || evidenceNeeds == null || evidenceNeeds.isEmpty()
                    || owner == null || retrievalQueries == null
                    || retrievalQueries.stream().anyMatch(query -> query == null || query.isBlank())) {
                throw new IllegalArgumentException("planned answer subquestion is invalid");
            }
            questionSpan = questionSpan.strip();
            evidenceNeeds = Set.copyOf(evidenceNeeds);
            List<String> normalizedRetrievalQueries = retrievalQueries.stream()
                    .map(String::strip)
                    .distinct()
                    .toList();
            if (normalizedRetrievalQueries.size() != retrievalQueries.size()) {
                throw new IllegalArgumentException("planned answer retrieval queries must be unique");
            }
            retrievalQueries = normalizedRetrievalQueries;
        }

        public PlannedSubquestion(
                String questionSpan,
                Set<EvidenceNeed> evidenceNeeds,
                SubquestionOwner owner) {
            this(questionSpan, evidenceNeeds, owner, List.of());
        }

        public PlannedSubquestion(String questionSpan, Set<EvidenceNeed> evidenceNeeds) {
            this(questionSpan, evidenceNeeds, SubquestionOwner.CURRENT_QUESTION, List.of());
        }
    }

    /** One explicit page locator copied from the current question; it is never rule evidence by itself. */
    record PlannedPageHint(String questionSpan, int pageNumber) {
        public PlannedPageHint {
            if (questionSpan == null || questionSpan.isBlank() || pageNumber < 1) {
                throw new IllegalArgumentException("planned answer page hint is invalid");
            }
            questionSpan = questionSpan.strip();
        }
    }

    record QuestionInterpretationDraft(
            QuestionType questionType,
            ReferenceBinding referenceBinding,
            List<String> terms,
            List<String> ruleObjectSpans,
            List<PlannedPageHint> pageHints,
            Set<MissingQuestionContext> missingContext,
            LearningIntent learningIntent,
            AnswerAid answerAid,
            List<PlannedSubquestion> subquestions) {
        public QuestionInterpretationDraft {
            if (questionType == null || referenceBinding == null || terms == null
                    || ruleObjectSpans == null || pageHints == null || missingContext == null
                    || answerAid == null || subquestions == null) {
                throw new IllegalArgumentException("question interpretation draft is invalid");
            }
            if (terms.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("question interpretation term is invalid");
            }
            if (ruleObjectSpans.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("question interpretation rule object is invalid");
            }
            List<String> normalizedTerms = terms.stream()
                    .map(String::strip)
                    .distinct()
                    .toList();
            List<String> normalizedRuleObjects = ruleObjectSpans.stream()
                    .map(String::strip)
                    .distinct()
                    .toList();
            List<PlannedPageHint> distinctPageHints = pageHints.stream().distinct().toList();
            if (normalizedTerms.size() != terms.size()
                    || normalizedRuleObjects.size() != ruleObjectSpans.size()
                    || distinctPageHints.size() != pageHints.size()) {
                throw new IllegalArgumentException("question interpretation arrays must not contain duplicates");
            }
            terms = normalizedTerms;
            ruleObjectSpans = normalizedRuleObjects;
            pageHints = distinctPageHints;
            missingContext = Set.copyOf(missingContext);
            subquestions = List.copyOf(subquestions);
        }

        public QuestionInterpretationDraft(
                QuestionType questionType,
                ReferenceBinding referenceBinding,
                List<String> terms,
                Set<MissingQuestionContext> missingContext,
                LearningIntent learningIntent,
                AnswerAid answerAid,
                List<PlannedSubquestion> subquestions) {
            this(
                    questionType,
                    referenceBinding,
                    terms,
                    List.of(),
                    List.of(),
                    missingContext,
                    learningIntent,
                    answerAid,
                    subquestions);
        }

        public QuestionInterpretationDraft(
                QuestionType questionType,
                ReferenceBinding referenceBinding,
                List<String> terms,
                Set<MissingQuestionContext> missingContext,
                LearningIntent learningIntent,
                List<PlannedSubquestion> subquestions) {
            this(
                    questionType,
                    referenceBinding,
                    terms,
                    List.of(),
                    List.of(),
                    missingContext,
                    learningIntent,
                    AnswerAid.forLearningIntent(learningIntent),
                    subquestions);
        }

        public QuestionInterpretationDraft(
                QuestionType questionType,
                ReferenceBinding referenceBinding,
                List<String> terms,
                Set<MissingQuestionContext> missingContext,
                List<PlannedSubquestion> subquestions) {
            this(
                    questionType,
                    referenceBinding,
                    terms,
                    List.of(),
                    List.of(),
                    missingContext,
                    null,
                    AnswerAid.NONE,
                    subquestions);
        }
    }

    record ModelRequest(
            String question,
            QuestionType questionType,
            AnswerContext context,
            List<EvidenceInput> evidence,
            Set<EvidenceNeed> evidenceNeeds,
            AnswerAid answerAid,
            List<PlannedSubquestion> subquestions) {
        public ModelRequest {
            if (question == null || question.isBlank() || questionType == null || context == null
                    || evidence == null || evidence.isEmpty() || evidenceNeeds == null || answerAid == null
                    || subquestions == null) {
                throw new IllegalArgumentException("answer model request is invalid");
            }
            evidence = List.copyOf(evidence);
            evidenceNeeds = Set.copyOf(evidenceNeeds);
            subquestions = List.copyOf(subquestions);
        }

        public ModelRequest(
                String question,
                QuestionType questionType,
                AnswerContext context,
                List<EvidenceInput> evidence,
                Set<EvidenceNeed> evidenceNeeds,
                AnswerAid answerAid) {
            this(question, questionType, context, evidence, evidenceNeeds, answerAid, List.of());
        }

        public ModelRequest(
                String question,
                QuestionType questionType,
                AnswerContext context,
                List<EvidenceInput> evidence,
                Set<EvidenceNeed> evidenceNeeds) {
            this(
                    question,
                    questionType,
                    context,
                    evidence,
                    evidenceNeeds,
                    AnswerAid.forLearningIntent(context.learningIntent()),
                    List.of());
        }

        public ModelRequest(
                String question,
                QuestionType questionType,
                AnswerContext context,
                List<EvidenceInput> evidence) {
            this(
                    question,
                    questionType,
                    context,
                    evidence,
                    Set.of(EvidenceNeed.DIRECT_RULE),
                    AnswerAid.forLearningIntent(context.learningIntent()),
                    List.of());
        }
    }

    record AnswerContext(
            String previousQuestion,
            LearningIntent learningIntent,
            PlayerLocale outputLanguage,
            ReferenceBinding referenceBinding,
            List<String> currentRuleObjectSpans,
            List<Integer> pageHints,
            EvidenceCoverage evidenceCoverage) {

        public AnswerContext {
            previousQuestion = optional(previousQuestion);
            outputLanguage = outputLanguage == null ? PlayerLocale.ZH_CN : outputLanguage;
            referenceBinding = referenceBinding == null ? ReferenceBinding.CURRENT_QUESTION : referenceBinding;
            evidenceCoverage = evidenceCoverage == null ? EvidenceCoverage.COMPLETE : evidenceCoverage;
            currentRuleObjectSpans = currentRuleObjectSpans == null
                    ? List.of()
                    : currentRuleObjectSpans.stream().map(String::strip).distinct().toList();
            pageHints = pageHints == null ? List.of() : pageHints.stream().distinct().toList();
            if (currentRuleObjectSpans.stream().anyMatch(String::isBlank)
                    || pageHints.stream().anyMatch(page -> page == null || page < 1)) {
                throw new IllegalArgumentException("answer context focus is invalid");
            }
        }

        public AnswerContext(
                String previousQuestion,
                LearningIntent learningIntent,
                PlayerLocale outputLanguage,
                ReferenceBinding referenceBinding,
                List<String> currentRuleObjectSpans,
                List<Integer> pageHints) {
            this(
                    previousQuestion,
                    learningIntent,
                    outputLanguage,
                    referenceBinding,
                    currentRuleObjectSpans,
                    pageHints,
                    EvidenceCoverage.COMPLETE);
        }

        public AnswerContext(
                String previousQuestion,
                LearningIntent learningIntent,
                PlayerLocale outputLanguage) {
            this(
                    previousQuestion,
                    learningIntent,
                    outputLanguage,
                    ReferenceBinding.CURRENT_QUESTION,
                    List.of(),
                    List.of(),
                    EvidenceCoverage.COMPLETE);
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "not provided" : value;
        }

        public String learningIntentForPrompt() {
            return learningIntent == null ? "GENERAL_QUESTION" : learningIntent.name();
        }

        public String outputLanguageForPrompt() {
            return outputLanguage.promptName();
        }
    }

    record EvidenceInput(
            UUID chunkId,
            String sectionType,
            String heading,
            String excerpt,
            String visualFacts,
            EvidenceContentKind contentKind,
            int pageFrom,
            int pageTo) {

        public EvidenceInput(
                UUID chunkId,
                String sectionType,
                String heading,
                String excerpt,
                int pageFrom,
                int pageTo) {
            this(
                    chunkId,
                    sectionType,
                    heading,
                    excerpt,
                    null,
                    EvidenceContentKind.CANONICAL_TEXT,
                    pageFrom,
                    pageTo);
        }

        public EvidenceInput {
            if (chunkId == null || sectionType == null || sectionType.isBlank()
                    || heading == null || heading.isBlank() || excerpt == null || excerpt.isBlank()
                    || contentKind == null || pageFrom < 1 || pageTo < pageFrom) {
                throw new IllegalArgumentException("answer evidence input is invalid");
            }
            visualFacts = visualFacts == null || visualFacts.isBlank() ? null : visualFacts.strip();
        }
    }

    enum EvidenceContentKind {
        CANONICAL_TEXT,
        VISUAL_PLACEHOLDER,
        CANONICAL_TEXT_WITH_VISUAL_FACTS,
        VISUAL_TRANSCRIPTION
    }

    /**
     * Untrusted request for application-controlled arithmetic. The model must bind every expression literal to a
     * typed source declaration and state the expected result; the application verifies both before publishing its
     * independently computed result. Player-facing prose remains free text and is never parsed as a numeric protocol.
     */
    record CalculationRequest(
            String expression,
            BigDecimal expectedResult,
            String resultUnit,
            List<CalculationOperandRequest> operands) {

        public CalculationRequest {
            // Expression size remains bounded by BoundedRuleCalculator's deliberately small, non-code-executing
            // parser. Display labels and provenance collections are content, so this transport record must not
            // impose additional handwritten ceilings on them.
            if (expression == null || expression.isBlank() || expression.length() > 160
                    || expectedResult == null
                    || resultUnit == null
                    || operands == null || operands.isEmpty()
                    || operands.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("calculation request is invalid");
            }
            expression = expression.strip();
            resultUnit = resultUnit.strip();
            operands = List.copyOf(operands);
        }
    }

    /** A typed provenance assertion for one occurrence of a numeric literal in a calculation expression. */
    record CalculationOperandRequest(
            String name,
            BigDecimal value,
            CalculationOperandSource source,
            String sourceSpan,
            UUID citationId) {

        public CalculationOperandRequest {
            if (name == null || name.isBlank()
                    || value == null
                    || source == null
                    || sourceSpan == null || sourceSpan.isBlank()
                    || (source == CalculationOperandSource.QUESTION && citationId != null)
                    || (source == CalculationOperandSource.EVIDENCE && citationId == null)) {
                throw new IllegalArgumentException("calculation operand request is invalid");
            }
            name = name.strip();
            sourceSpan = sourceSpan.strip();
        }
    }

    enum CalculationOperandSource {
        QUESTION,
        EVIDENCE
    }

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
            WalkthroughOrderBasis orderBasis,
            List<UUID> citationIds) {

        public WalkthroughStepRequest(
                String instruction,
                String explanation,
                String orderBasis,
                List<UUID> citationIds) {
            this(instruction, explanation, strictEnum(WalkthroughOrderBasis.class, orderBasis), citationIds);
        }
    }

    /** Untrusted proposed condition/outcome branch tied to explicit rulebook evidence. */
    record DecisionBranchRequest(
            String condition,
            String outcome,
            DecisionBranchBasis basis,
            List<UUID> citationIds) {

        public DecisionBranchRequest(
                String condition,
                String outcome,
                String basis,
                List<UUID> citationIds) {
            this(condition, outcome, strictEnum(DecisionBranchBasis.class, basis), citationIds);
        }
    }

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
            WorkedExampleBasis basis,
            List<UUID> citationIds) {

        public WorkedExampleRequest(
                String setup,
                String action,
                String outcome,
                String basis,
                List<UUID> citationIds) {
            this(setup, action, outcome, strictEnum(WorkedExampleBasis.class, basis), citationIds);
        }
    }

    /** Untrusted comparison of two rules whose priority must be explicitly supported by cited evidence. */
    record RulePriorityRequest(
            String baseRule,
            String competingRule,
            String resolution,
            RulePriorityBasis basis,
            List<UUID> citationIds) {

        public RulePriorityRequest(
                String baseRule,
                String competingRule,
                String resolution,
                String basis,
                List<UUID> citationIds) {
            this(baseRule, competingRule, resolution, strictEnum(RulePriorityBasis.class, basis), citationIds);
        }
    }

    /** Untrusted simultaneous-effect ordering whose source must be explicit in cited evidence. */
    record RuleTimingRequest(
            String timingContext,
            String resolutionOrder,
            String orderSource,
            TimingOrderBasis basis,
            List<UUID> citationIds) {

        public RuleTimingRequest(
                String timingContext,
                String resolutionOrder,
                String orderSource,
                String basis,
                List<UUID> citationIds) {
            this(timingContext, resolutionOrder, orderSource, strictEnum(TimingOrderBasis.class, basis), citationIds);
        }
    }

    /** Untrusted ordered tie ruling whose steps and terminal outcome must all be explicit in cited evidence. */
    record RuleTieRequest(
            String tieContext,
            List<String> resolutionSteps,
            String finalOutcome,
            TieResolutionBasis basis,
            List<UUID> citationIds) {

        public RuleTieRequest(
                String tieContext,
                List<String> resolutionSteps,
                String finalOutcome,
                String basis,
                List<UUID> citationIds) {
            this(tieContext, resolutionSteps, finalOutcome, strictEnum(TieResolutionBasis.class, basis), citationIds);
        }
    }

    /** Untrusted applicability ruling matched against scope facts stated in the current question. */
    record RuleScopeRequest(
            String ruleContext,
            String governingCondition,
            String currentSituation,
            ScopeMatchStatus matchStatus,
            String effect,
            ScopeBasis basis,
            List<UUID> citationIds) {

        public RuleScopeRequest(
                String ruleContext,
                String governingCondition,
                String currentSituation,
                String matchStatus,
                String effect,
                String basis,
                List<UUID> citationIds) {
            this(
                    ruleContext,
                    governingCondition,
                    currentSituation,
                    strictEnum(ScopeMatchStatus.class, matchStatus),
                    effect,
                    strictEnum(ScopeBasis.class, basis),
                    citationIds);
        }
    }

    /** Untrusted side-by-side distinction between two rulebook concepts named by the player. */
    record RuleConceptComparisonRequest(
            String leftConcept,
            String leftDefinition,
            String rightConcept,
            String rightDefinition,
            String commonGround,
            String keyDifference,
            String practicalBoundary,
            ConceptComparisonBasis basis,
            List<UUID> citationIds) {

        public RuleConceptComparisonRequest(
                String leftConcept,
                String leftDefinition,
                String rightConcept,
                String rightDefinition,
                String commonGround,
                String keyDifference,
                String practicalBoundary,
                String basis,
                List<UUID> citationIds) {
            this(
                    leftConcept,
                    leftDefinition,
                    rightConcept,
                    rightDefinition,
                    commonGround,
                    keyDifference,
                    practicalBoundary,
                    strictEnum(ConceptComparisonBasis.class, basis),
                    citationIds);
        }
    }

    /** Untrusted member of a claimed complete, cited option set requested by the player. */
    record RuleOptionRequest(
            String decisionContext,
            String selectionRule,
            String optionName,
            String availabilityCondition,
            String result,
            RuleOptionBasis basis,
            List<UUID> citationIds) {

        public RuleOptionRequest(
                String decisionContext,
                String selectionRule,
                String optionName,
                String availabilityCondition,
                String result,
                String basis,
                List<UUID> citationIds) {
            this(
                    decisionContext,
                    selectionRule,
                    optionName,
                    availabilityCondition,
                    result,
                    strictEnum(RuleOptionBasis.class, basis),
                    citationIds);
        }
    }

    record ModelDraft(
            boolean answerable,
            String insufficiencyReason,
            String shortVerdict,
            String explanation,
            List<UUID> citationIds,
            List<String> exceptions,
            AnswerConfidence confidence,
            AnswerBasis answerBasis,
            List<CalculationRequest> calculations,
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
            if (answerable && (confidence == null || answerBasis == null)) {
                throw new IllegalArgumentException("answerable model draft requires typed confidence and answer basis");
            }
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
                List<RuleConceptComparisonRequest> conceptComparisons,
                List<RuleOptionRequest> ruleOptions) {
            this(
                    answerable,
                    insufficiencyReason,
                    shortVerdict,
                    explanation,
                    citationIds,
                    exceptions,
                    strictEnum(AnswerConfidence.class, confidence),
                    strictEnum(AnswerBasis.class, answerBasis),
                    calculations,
                    walkthroughSteps,
                    decisionBranches,
                    exceptionClauses,
                    termDefinitions,
                    workedExamples,
                    priorityResolutions,
                    timingResolutions,
                    tieResolutions,
                    scopeResolutions,
                    conceptComparisons,
                    ruleOptions);
            if (situationChecks != null && !situationChecks.isEmpty()) {
                throw new IllegalArgumentException("model-generated situation checks are not accepted");
            }
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

    private static <T extends Enum<T>> T strictEnum(Class<T> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalidValue) {
            String field = type.getSimpleName().endsWith("Basis") ? "basis"
                    : type == ScopeMatchStatus.class ? "status"
                    : type.getSimpleName();
            throw new IllegalArgumentException(field + " is invalid: " + value, invalidValue);
        }
    }
}
