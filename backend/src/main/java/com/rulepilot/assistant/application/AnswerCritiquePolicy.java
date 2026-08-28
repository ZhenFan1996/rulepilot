package com.rulepilot.assistant.application;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.AnswerBasis;
import com.rulepilot.assistant.domain.AnswerConfidence;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Prepares an optional semantic, evidence-grounded review for generated answers. */
final class AnswerCritiquePolicy {

    private AnswerCritiquePolicy() {}

    static ReviewRisk reviewRisk(
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer) {
        return reviewRisk(question, context, null, answer);
    }

    static ReviewRisk reviewRisk(
            UnderstoodQuestion question,
            QuestionContext context,
            ModelRequest modelRequest,
            StructuredRuleAnswer answer) {
        if (answer.confidence() == AnswerConfidence.LOW) {
            return ReviewRisk.LOW_CONFIDENCE;
        }
        if (answer.answerBasis() == AnswerBasis.GROUNDED_APPLICATION
                || requiresCrossRuleSynthesis(answer)
                || requiresPlannedEvidenceSynthesis(modelRequest, answer)) {
            return ReviewRisk.HIGH_IMPACT;
        }
        // The candidate reached this policy only after schema, source/version ownership, citation-ID, and
        // structured-aid publication gates. Those deterministic boundaries are the normal runtime protection;
        // semantic Critic execution remains available in explicit evaluation mode instead of adding two paid model
        // calls to every ordinary answer.
        return ReviewRisk.STANDARD;
    }

    private static boolean requiresCrossRuleSynthesis(StructuredRuleAnswer answer) {
        if (answer.citations().size() < 2) return false;
        return !answer.priorityResolutions().isEmpty()
                || !answer.timingResolutions().isEmpty()
                || !answer.tieResolutions().isEmpty()
                || !answer.scopeResolutions().isEmpty();
    }

    private static boolean requiresPlannedEvidenceSynthesis(
            ModelRequest modelRequest, StructuredRuleAnswer answer) {
        if (modelRequest == null || answer.citations().size() < 2) return false;
        var needs = modelRequest.evidenceNeeds();
        return needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.RELATIONSHIP)
                || needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.EXCEPTION)
                || needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.COMPLETE_LIST)
                        && needs.contains(com.rulepilot.assistant.RuleAnswerModel.EvidenceNeed.CONDITION);
    }

    static ReviewRequest request(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        return request(assistantRunId, question, context, null, answer, evidence);
    }

    static ReviewRequest request(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            ModelRequest modelRequest,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        List<UUID> answerCitations = answer.citations().stream().map(RuleCitation::chunkId).toList();
        List<Claim> claims = claims(answer, answerCitations);
        String selectedReference = modelRequest == null
                ? ""
                : modelRequest.context().previousQuestion();
        String focusContract = modelRequest == null
                ? ""
                : " The validated current rule-object spans are "
                        + modelRequest.context().currentRuleObjectSpans()
                        + ". Explicit page locators are "
                        + modelRequest.context().pageHints()
                        + "; a page locator narrows where to inspect but is never proof of a rule claim.";
        return new ReviewRequest(
                assistantRunId,
                ContentType.ANSWER,
                new TaskContext(
                        "Answer the player's current rule question: " + question.originalQuestion()
                                + (selectedReference == null || selectedReference.isBlank()
                                                || "not provided".equals(selectedReference)
                                        ? ""
                                        : " Selected reference context (reference resolution only): " + selectedReference),
                        "Judge every player-facing claim against its own combined citations. Preserve actor, action, "
                                + "object, condition, quantity, modality, timing, sequence, result, exception, and "
                                + "scope. Cover every material obligation in the question and the selected structured "
                                + "aid. For numerical claims, preserve the aggregation unit, per-item or per-category "
                                + "scope, repetition count, multiplier, cap, and exception together. Treat a supplied "
                                + "same-scope worked example as a consistency check on the governing rule and total. "
                                + "Do not accept outside knowledge, invented table state, inferred strategy, or "
                                + "source-authored advice without directly cited advice text. Natural paraphrase and "
                                + "faithful translation are valid. The current question and its explicitly named "
                                + "object remain authoritative; selected prior context may resolve an omitted reference "
                                + "but may not replace a current object. Advertising, contents/listing text, error-page "
                                + "text, extraction placeholders, and descriptive visual metadata cannot entail a "
                                + "gameplay rule. A directly answering source clause can." + focusContract),
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
    }

    private static List<Claim> claims(StructuredRuleAnswer answer, List<UUID> answerCitations) {
        List<Claim> claims = new ArrayList<>();
        add(claims, answer.shortVerdict(), answerCitations);
        add(claims, answer.explanation(), answerCitations);
        answer.exceptions().forEach(value -> add(claims, value, answerCitations));
        answer.calculations().forEach(value -> add(
                claims, "Calculation: " + value.expression() + " = " + value.result(), answerCitations));
        answer.situationChecks().forEach(value -> add(
                claims,
                "Situation: " + value.requirement() + "; status=" + value.status()
                        + "; playerFact=" + value.playerFact(),
                value.citationIds()));
        answer.walkthroughSteps().forEach(value -> add(
                claims,
                "Walkthrough; orderBasis=" + value.orderBasis() + "; instruction=" + value.instruction()
                        + "; explanation=" + value.explanation(),
                value.citationIds()));
        answer.decisionBranches().forEach(value -> add(
                claims,
                "Decision branch; basis=" + value.basis() + "; condition=" + value.condition()
                        + "; outcome=" + value.outcome(),
                value.citationIds()));
        answer.exceptionClauses().forEach(value -> add(
                claims, "Exception; condition=" + value.condition() + "; effect=" + value.effect(), value.citationIds()));
        answer.termDefinitions().forEach(value -> add(
                claims,
                "Definition; term=" + value.term() + "; definition=" + value.definition()
                        + "; boundary=" + value.boundary(),
                value.citationIds()));
        answer.workedExamples().forEach(value -> add(
                claims,
                "Example; basis=" + value.basis() + "; setup=" + value.setup() + "; action=" + value.action()
                        + "; outcome=" + value.outcome(),
                value.citationIds()));
        answer.priorityResolutions().forEach(value -> add(
                claims,
                "Priority; basis=" + value.basis() + "; base=" + value.baseRule() + "; competing="
                        + value.competingRule() + "; resolution=" + value.resolution(),
                value.citationIds()));
        answer.timingResolutions().forEach(value -> add(
                claims,
                "Timing; basis=" + value.basis() + "; context=" + value.timingContext() + "; order="
                        + value.resolutionOrder() + "; source=" + value.orderSource(),
                value.citationIds()));
        answer.tieResolutions().forEach(value -> add(
                claims,
                "Tie; basis=" + value.basis() + "; context=" + value.tieContext() + "; steps="
                        + String.join(" -> ", value.resolutionSteps()) + "; outcome=" + value.finalOutcome(),
                value.citationIds()));
        answer.scopeResolutions().forEach(value -> add(
                claims,
                "Scope; basis=" + value.basis() + "; status=" + value.matchStatus() + "; rule="
                        + value.ruleContext() + "; condition=" + value.governingCondition() + "; situation="
                        + value.currentSituation() + "; effect=" + value.effect(),
                value.citationIds()));
        answer.conceptComparisons().forEach(value -> add(
                claims,
                "Comparison; basis=" + value.basis() + "; left=" + value.leftConcept() + ": "
                        + value.leftDefinition() + "; right=" + value.rightConcept() + ": "
                        + value.rightDefinition() + "; common=" + value.commonGround() + "; difference="
                        + value.keyDifference() + "; boundary=" + value.practicalBoundary(),
                value.citationIds()));
        answer.ruleOptions().forEach(value -> add(
                claims,
                "Option; basis=" + value.basis() + "; context=" + value.decisionContext() + "; selection="
                        + value.selectionRule() + "; name=" + value.optionName() + "; availability="
                        + value.availabilityCondition() + "; result=" + value.result(),
                value.citationIds()));
        return List.copyOf(claims);
    }

    private static void add(List<Claim> claims, String text, List<UUID> citations) {
        claims.add(new Claim(claims.size() + 1, text, citations));
    }

}
