package com.rulepilot.assistant.application;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure preparation for a bounded answer critique; retrieval and model calls stay in the answer workflow. */
final class AnswerCritiquePolicy {

    private static final Pattern MATERIAL_CONDITION = Pattern.compile(
            "(?iu)\\b(?:if|when|after|before|unless|whether|then|once)\\b|"
                    + "如果|若|当|除非|否则|之后|以后|之前|以前|怎么办|如何处理|是否|能否|现在能|现在可以|不能|必须");
    private static final Pattern COUNTERFACTUAL_FOLLOW_UP = Pattern.compile(
            "(?iu)\\b(?:if\\s+(?:so|possible|true|that\\s+is\\s+the\\s+case)|if\\s+it\\s+can|then\\s+what)\\b|"
                    + "如果可以|若可以|如果满足|若满足|如果是这样|那么(?:其他|接下来)|其他玩家.*(?:继续|还会)");

    private AnswerCritiquePolicy() {}

    static ReviewRisk reviewRisk(
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer) {
        if (allowsBoundedCorrection(question, context)) {
            return ReviewRisk.HIGH_IMPACT;
        }
        return answer.confidence() == AnswerConfidence.LOW ? ReviewRisk.LOW_CONFIDENCE : ReviewRisk.STANDARD;
    }

    static boolean allowsBoundedCorrection(
            UnderstoodQuestion question, QuestionContext context) {
        return context.previousQuestion() != null
                || context.learningIntent() != null
                || requiresDirectFactualReview(question);
    }

    /**
     * A conditional table ruling has an observable branch and an immediate game consequence. It is cheap to identify
     * before composition, but expensive for a player when a fluent answer silently turns the fallback branch into a
     * no-op. Keep ordinary definition questions fast while reviewing these decision points before publication.
     */
    private static boolean requiresDirectFactualReview(UnderstoodQuestion question) {
        return question.type() == com.rulepilot.assistant.domain.QuestionType.SITUATION_QUERY
                || AnswerEvidenceRefinementPolicy.asksAboutRuleRelationship(question.normalizedQuestion())
                || AnswerWalkthroughResolver.asksForProcedure(question.normalizedQuestion())
                || AnswerDecisionTableResolver.asksForBranches(question.normalizedQuestion())
                || AnswerTermDefinitionResolver.asksForDefinition(question.normalizedQuestion())
                || MATERIAL_CONDITION.matcher(question.normalizedQuestion()).find();
    }

    static ReviewRequest request(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        List<UUID> citationIds = answer.citations().stream().map(RuleCitation::chunkId).toList();
        List<Claim> claims = new ArrayList<>();
        claims.add(new Claim(1, answer.shortVerdict() + "\n" + answer.explanation(), citationIds));
        for (int index = 0; index < answer.exceptions().size(); index++) {
            claims.add(new Claim(index + 2, answer.exceptions().get(index), citationIds));
        }
        int calculationOffset = answer.exceptions().size() + 2;
        for (int index = 0; index < answer.calculations().size(); index++) {
            var calculation = answer.calculations().get(index);
            claims.add(new Claim(
                    calculationOffset + index,
                    "Derived calculation: " + calculation.expression() + " = " + calculation.result(),
                    citationIds));
        }
        int situationOffset = calculationOffset + answer.calculations().size();
        for (int index = 0; index < answer.situationChecks().size(); index++) {
            var check = answer.situationChecks().get(index);
            claims.add(new Claim(
                    situationOffset + index,
                    "Situation requirement: " + check.requirement() + "; status=" + check.status()
                            + "; literal player fact=" + (check.playerFact().isEmpty() ? "NOT_PROVIDED" : check.playerFact()),
                    check.citationIds()));
        }
        int walkthroughOffset = situationOffset + answer.situationChecks().size();
        for (int index = 0; index < answer.walkthroughSteps().size(); index++) {
            var step = answer.walkthroughSteps().get(index);
            claims.add(new Claim(
                    walkthroughOffset + index,
                    "Walkthrough step " + (index + 1) + "; orderBasis=" + step.orderBasis()
                            + "; instruction=" + step.instruction() + "; explanation=" + step.explanation(),
                    step.citationIds()));
        }
        int branchOffset = walkthroughOffset + answer.walkthroughSteps().size();
        for (int index = 0; index < answer.decisionBranches().size(); index++) {
            var branch = answer.decisionBranches().get(index);
            claims.add(new Claim(
                    branchOffset + index,
                    "Decision branch " + (index + 1) + "; basis=" + branch.basis()
                            + "; condition=" + branch.condition() + "; outcome=" + branch.outcome(),
                    branch.citationIds()));
        }
        int exceptionClauseOffset = branchOffset + answer.decisionBranches().size();
        for (int index = 0; index < answer.exceptionClauses().size(); index++) {
            var clause = answer.exceptionClauses().get(index);
            claims.add(new Claim(
                    exceptionClauseOffset + index,
                    "Exception or restriction " + (index + 1) + "; condition=" + clause.condition()
                            + "; effect=" + clause.effect(),
                    clause.citationIds()));
        }
        int definitionOffset = exceptionClauseOffset + answer.exceptionClauses().size();
        for (int index = 0; index < answer.termDefinitions().size(); index++) {
            var definition = answer.termDefinitions().get(index);
            claims.add(new Claim(
                    definitionOffset + index,
                    "Rulebook term " + definition.term() + "; definition=" + definition.definition()
                            + "; boundary=" + definition.boundary(),
                    definition.citationIds()));
        }
        int workedExampleOffset = definitionOffset + answer.termDefinitions().size();
        for (int index = 0; index < answer.workedExamples().size(); index++) {
            var example = answer.workedExamples().get(index);
            claims.add(new Claim(
                    workedExampleOffset + index,
                    "Worked example " + (index + 1) + "; basis=" + example.basis()
                            + "; setup=" + example.setup() + "; action=" + example.action()
                            + "; outcome=" + example.outcome(),
                    example.citationIds()));
        }
        int priorityOffset = workedExampleOffset + answer.workedExamples().size();
        for (int index = 0; index < answer.priorityResolutions().size(); index++) {
            var resolution = answer.priorityResolutions().get(index);
            claims.add(new Claim(
                    priorityOffset + index,
                    "Rule priority resolution " + (index + 1) + "; basis=" + resolution.basis()
                            + "; baseRule=" + resolution.baseRule()
                            + "; competingRule=" + resolution.competingRule()
                            + "; resolution=" + resolution.resolution(),
                    resolution.citationIds()));
        }
        int timingOffset = priorityOffset + answer.priorityResolutions().size();
        for (int index = 0; index < answer.timingResolutions().size(); index++) {
            var resolution = answer.timingResolutions().get(index);
            claims.add(new Claim(
                    timingOffset + index,
                    "Timing resolution " + (index + 1) + "; basis=" + resolution.basis()
                            + "; timingContext=" + resolution.timingContext()
                            + "; resolutionOrder=" + resolution.resolutionOrder()
                            + "; orderSource=" + resolution.orderSource(),
                    resolution.citationIds()));
        }
        int tieOffset = timingOffset + answer.timingResolutions().size();
        for (int index = 0; index < answer.tieResolutions().size(); index++) {
            var resolution = answer.tieResolutions().get(index);
            claims.add(new Claim(
                    tieOffset + index,
                    "Tie resolution " + (index + 1) + "; basis=" + resolution.basis()
                            + "; tieContext=" + resolution.tieContext()
                            + "; resolutionSteps=" + String.join(" -> ", resolution.resolutionSteps())
                            + "; finalOutcome=" + resolution.finalOutcome(),
                    resolution.citationIds()));
        }
        int scopeOffset = tieOffset + answer.tieResolutions().size();
        for (int index = 0; index < answer.scopeResolutions().size(); index++) {
            var resolution = answer.scopeResolutions().get(index);
            claims.add(new Claim(
                    scopeOffset + index,
                    "Scope resolution " + (index + 1) + "; basis=" + resolution.basis()
                            + "; status=" + resolution.matchStatus()
                            + "; ruleContext=" + resolution.ruleContext()
                            + "; governingCondition=" + resolution.governingCondition()
                            + "; currentSituation=" + resolution.currentSituation()
                            + "; effect=" + resolution.effect(),
                    resolution.citationIds()));
        }
        int comparisonOffset = scopeOffset + answer.scopeResolutions().size();
        for (int index = 0; index < answer.conceptComparisons().size(); index++) {
            var comparison = answer.conceptComparisons().get(index);
            claims.add(new Claim(
                    comparisonOffset + index,
                    "Rule concept comparison " + (index + 1) + "; basis=" + comparison.basis()
                            + "; left=" + comparison.leftConcept() + ": " + comparison.leftDefinition()
                            + "; right=" + comparison.rightConcept() + ": " + comparison.rightDefinition()
                            + "; commonGround=" + comparison.commonGround()
                            + "; keyDifference=" + comparison.keyDifference()
                            + "; practicalBoundary=" + comparison.practicalBoundary(),
                    comparison.citationIds()));
        }
        int optionOffset = comparisonOffset + answer.conceptComparisons().size();
        for (int index = 0; index < answer.ruleOptions().size(); index++) {
            var option = answer.ruleOptions().get(index);
            claims.add(new Claim(
                    optionOffset + index,
                    "Rule option " + (index + 1) + "; basis=" + option.basis()
                            + "; decisionContext=" + option.decisionContext()
                            + "; selectionRule=" + option.selectionRule()
                            + "; optionName=" + option.optionName()
                            + "; availabilityCondition=" + option.availabilityCondition()
                            + "; result=" + option.result(),
                    option.citationIds()));
        }
        return new ReviewRequest(
                assistantRunId,
                ContentType.ANSWER,
                new TaskContext(
                        "Answer the user's normalized rule question: " + question.normalizedQuestion()
                                + "; previous question for reference resolution only: "
                                + contextValue(context.previousQuestion()),
                        "Give a supported verdict and explanation for question type " + question.type()
                                + "; answer basis " + (answer.answerBasis() == null
                                        ? "not applicable"
                                        : answer.answerBasis().name())
                                + "; preserve material exceptions and learning intent "
                                + contextValue(context.learningIntent())
                                + ". Preserve every named eligibility and identity condition. Reject any claim that a "
                                + "condition is irrelevant, optional, or broader than stated unless evidence explicitly "
                                + "says so. For an 'again' follow-up, reject any repeatability claim not explicitly "
                                + "supported by evidence. A GROUNDED_APPLICATION may combine cited premises only to "
                                + "apply the player's explicitly stated table condition; reject it if it invents a game "
                                + "fact or silently assumes a missing branch. For a derived calculation, verify that "
                                + "a requested option list includes every cited named option and preserves exact "
                                + "counts, mandatory or optional selection, timing restrictions, repeatability, and "
                                + "each option's immediate after-effect. Reject merged, invented, or omitted options. "
                                + "the cited rule supports how the grounded operands are combined; arithmetic execution "
                                + "is deterministic, but the chosen formula must still match the rule. Reject any "
                                + "claimed exception, replacement, or precedence based only on a special-sounding "
                                + "heading, document order, or assumed specific-over-general convention; require cited "
                                + "applicability language for the exact actor, object, action, condition, and timing. "
                                + "For each situation requirement, verify both that the cited rule establishes the "
                                + "requirement and that the literal player fact semantically confirms or contradicts "
                                + "it. NOT_PROVIDED must never become an assumed table fact or unconditional verdict. "
                                + "For each walkthrough step, verify its instruction and explanation against its own "
                                + "citations. RULE_ORDER requires evidence for that relative sequence; document layout "
                                + "or presentation order is insufficient. EXPLANATION_ORDER may improve readability but "
                                + "must not imply a mandatory gameplay order that the evidence does not establish."
                                + " For each decision branch, verify that its own citations establish the exact "
                                + "condition-to-outcome relationship. EXPLICIT_RULE requires direct rule language. "
                                + "RULEBOOK_EXAMPLE requires the source itself to frame the case as an example and "
                                + "must not be generalized. Reject invented fallback branches, merged conditions, "
                                + "swapped outcomes, or a material evidenced branch omitted from a requested comparison."
                                + " For each exception or restriction, verify that its own citations directly "
                                + "establish the exact condition-to-effect relationship. Reject a clause inferred "
                                + "only from a heading, nearby passage, document order, genre convention, or example. "
                                + "When the player explicitly asks for exceptions, reject an answer that leaves an "
                                + "evidenced material exception only in prose or the legacy uncited exceptions list."
                                + " For each term definition, require its own citations to directly state the meaning. "
                                + "A mention, label, example, consequence, or nearby relationship is not a definition. "
                                + "Verify every boundary or contrast separately and reject any distinction supplied "
                                + "from model knowledge rather than the active rulebook evidence."
                                + " For each worked example, verify setup, action, and outcome against its own citations. "
                                + "RULEBOOK_EXAMPLE requires the source to present that actual case; do not alter its "
                                + "starting quantities, sequence, payments, gains, names, or final result. "
                                + "EVIDENCE_BOUND_ILLUSTRATION may use neutral placeholders only and must not invent a "
                                + "number, named component, board position, resource, prerequisite, or outcome. Reject "
                                + "any example whose resource ledger spends or uses something before the cited setup "
                                + "provides it. When EXAMPLE is requested, reject prose-only examples."
                                + " For each rule priority resolution, require explicit cited relationship language. "
                                + "EXPLICIT_OVERRIDE requires the source to say one rule overrides or supersedes the "
                                + "other. IMPOSSIBILITY_PRIORITY requires the source to say an impossible effect has "
                                + "priority over a possible effect. CONFLICT_ONLY_OVERRIDE requires the source to say "
                                + "the competing rule replaces the base rule only when they conflict, while both must "
                                + "still be satisfied when possible. Reject priority inferred from headings, page order, "
                                + "specificity, theme, examples, or model knowledge. For a direct priority question, "
                                + "reject a prose-only answer."
                                + " For each timing resolution, require cited language that establishes the exact "
                                + "ordering authority. CURRENT_PLAYER_CHOOSES requires the source to assign the choice "
                                + "to the player taking the current turn. PRINTED_TOP_TO_BOTTOM requires the source to "
                                + "say effects resolve in their printed top-to-bottom order. NORMAL_TURN_ORDER requires "
                                + "the source to mandate normal turn order and preserve any named starting role. Reject "
                                + "timing inferred from page layout, card age, initiative, clockwise order, active-player "
                                + "convention, or model knowledge. For a direct timing-order question, reject a prose-only answer."
                                + counterfactualFollowUpRequirement(question.normalizedQuestion())),
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
    }

    static List<String> revisionFeedback(Review review) {
        return review.issues().stream()
                .map(issue -> issue.type().name() + ": " + issue.summary())
                .toList();
    }

    private static String contextValue(Object value) {
        return value == null ? "not provided" : value.toString();
    }

    private static String counterfactualFollowUpRequirement(String question) {
        if (question == null || !COUNTERFACTUAL_FOLLOW_UP.matcher(question).find()) return "";
        return " The player also asks for the stated counterfactual follow-up. Even if the immediate verdict is no, "
                + "require the answer to state what happens when the named trigger is satisfied whenever the cited "
                + "evidence provides that procedure.";
    }
}
