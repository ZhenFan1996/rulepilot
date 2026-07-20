package com.rulepilot.assistant.application;

import com.rulepilot.assistant.domain.AnswerFeedback;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AnswerFeedbackRepository {

    UUID save(AnswerFeedback feedback);

    Map<UUID, Rating> findRatings(Set<UUID> conversationTurnIds, String username);
}
