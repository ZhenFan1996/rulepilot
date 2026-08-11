package com.rulepilot.assistant.application;

import com.rulepilot.assistant.GeneratedContentCritic;
import com.rulepilot.assistant.GeneratedContentCritic.Claim;
import com.rulepilot.assistant.GeneratedContentCritic.ContentType;
import com.rulepilot.assistant.GeneratedContentCritic.Review;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRequest;
import com.rulepilot.assistant.GeneratedContentCritic.ReviewRisk;
import com.rulepilot.assistant.GeneratedContentCritic.TaskContext;
import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.RuleCitation;
import com.rulepilot.assistant.domain.StructuredRuleAnswer;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Prepares one semantic, evidence-grounded review for every generated answer. */
final class AnswerCritiquePolicy {

    private AnswerCritiquePolicy() {}

    static ReviewRisk reviewRisk(
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer) {
        return ReviewRisk.HIGH_IMPACT;
    }

    static boolean allowsBoundedCorrection(UnderstoodQuestion question, QuestionContext context) {
        return question != null && context != null;
    }

    static ReviewRequest request(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            StructuredRuleAnswer answer,
            List<HybridEvidenceHit> evidence) {
        List<UUID> answerCitations = answer.citations().stream().map(RuleCitation::chunkId).toList();
        List<Claim> claims = claims(answer, answerCitations);
        return new ReviewRequest(
                assistantRunId,
                ContentType.ANSWER,
                new TaskContext(
                        "Answer the player's rule question: " + question.normalizedQuestion(),
                        "Judge every player-facing claim against its own combined citations. Preserve actor, action, "
                                + "object, condition, quantity, modality, timing, sequence, result, exception, and "
                                + "scope. Cover every material obligation in the question and the selected structured "
                                + "aid. Do not accept outside knowledge, invented table state, inferred strategy, or "
                                + "source-authored advice without directly cited advice text. Natural paraphrase and "
                                + "faithful translation are valid."),
                claims,
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(source -> new GeneratedContentCritic.Evidence(source.chunkId(), source.excerpt()))
                        .toList());
    }

    private static List<Claim> claims(StructuredRuleAnswer answer, List<UUID> answerCitations) {
        List<Claim> claims = new ArrayList<>();
        add(claims, answer.shortVerdict() + "\n" + answer.explanation(), answerCitations);
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

    static List<String> revisionFeedback(Review review) {
        return review.issues().stream()
                .map(issue -> issue.type().name() + ": " + issue.summary())
                .toList();
    }
}
