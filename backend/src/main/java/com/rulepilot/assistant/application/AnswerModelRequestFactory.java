package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.RuleAnswerModel.AnswerContext;
import com.rulepilot.assistant.RuleAnswerModel.EvidenceInput;
import com.rulepilot.assistant.RuleAnswerModel.ModelRequest;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import com.rulepilot.retrieval.evidence.HybridEvidenceHit;
import java.util.List;

final class AnswerModelRequestFactory {

    ModelRequest create(
            UnderstoodQuestion question, QuestionContext context, List<HybridEvidenceHit> evidence) {
        return new ModelRequest(
                question.normalizedQuestion(),
                question.type(),
                new AnswerContext(
                        context.currentLessonSection(),
                        context.gamePhase(),
                        context.playerCount(),
                        context.activeExpansions().size(),
                        context.previousQuestion(),
                        context.learningIntent(),
                        context.outputLanguage()),
                evidence.stream()
                        .map(HybridEvidenceHit::evidence)
                        .map(hit -> new EvidenceInput(
                                hit.chunkId(),
                                hit.sectionType(),
                                hit.heading(),
                                hit.excerpt(),
                                hit.pageFrom(),
                                hit.pageTo()))
                        .toList());
    }
}
