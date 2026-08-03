package com.rulepilot.assistant.application;

import com.rulepilot.assistant.QuestionUnderstanding.QuestionContext;
import com.rulepilot.assistant.domain.UnderstoodQuestion;
import java.util.UUID;

/** Stable boundary between answer orchestration and optional evidence acquisition. */
public interface AnswerEvidenceRefiner {

    AnswerEvidenceRetriever.Result refine(
            UUID assistantRunId,
            UnderstoodQuestion question,
            QuestionContext context,
            String username,
            UUID gameSessionId,
            AnswerEvidenceRetriever.Result deterministic);
}
