package com.rulepilot.assistant.adapter.out.persistence;

import com.rulepilot.assistant.application.AnswerFeedbackRepository;
import com.rulepilot.assistant.domain.AnswerFeedback;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JpaAnswerFeedbackRepository implements AnswerFeedbackRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public UUID save(AnswerFeedback feedback) {
        Object id = entityManager.createNativeQuery("""
                        insert into answer_feedback (
                            id, conversation_turn_id, game_session_id, rating, created_by, created_at
                        ) values (
                            :id, :turnId, :sessionId, :rating, :createdBy, :createdAt
                        )
                        on conflict (conversation_turn_id, created_by) do update
                        set rating = excluded.rating, created_at = excluded.created_at
                        returning id
                        """)
                .setParameter("id", feedback.id())
                .setParameter("turnId", feedback.conversationTurnId())
                .setParameter("sessionId", feedback.gameSessionId())
                .setParameter("rating", feedback.rating().name())
                .setParameter("createdBy", feedback.createdBy())
                .setParameter("createdAt", feedback.createdAt())
                .getSingleResult();
        return id instanceof UUID uuid ? uuid : UUID.fromString(id.toString());
    }
}
