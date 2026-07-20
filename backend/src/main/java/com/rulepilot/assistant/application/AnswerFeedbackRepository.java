package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.AnswerFeedback;
import java.util.UUID;

public interface AnswerFeedbackRepository {

    UUID save(AnswerFeedback feedback);
}
