package com.rulepilot.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.assistant.domain.AnswerFeedback;
import com.rulepilot.assistant.domain.AnswerFeedback.Rating;
import com.rulepilot.assistant.domain.GameSessionConversationTurn;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnswerFeedbackServiceTest {

    @Test
    void onlyAcceptsFeedbackForAnOwnedConversationTurn() {
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID persistedId = UUID.randomUUID();
        StubConversations conversations = new StubConversations(sessionId, turnId, "alice");
        CapturingFeedback feedback = new CapturingFeedback(persistedId);
        AnswerFeedbackService service = new AnswerFeedbackService(conversations, feedback);

        AnswerFeedback submitted = service.submit(sessionId, turnId, Rating.UNCLEAR, "alice");

        assertThat(submitted.id()).isEqualTo(persistedId);
        assertThat(feedback.saved.rating()).isEqualTo(Rating.UNCLEAR);
        assertThatThrownBy(() -> service.submit(sessionId, turnId, Rating.HELPFUL, "bob"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record StubConversations(UUID sessionId, UUID turnId, String owner)
            implements GameSessionConversationRepository {

        @Override
        public void save(GameSessionConversationTurn turn) {}

        @Override
        public java.util.List<GameSessionConversationTurn> findRecent(UUID id, String username, int limit) {
            return java.util.List.of();
        }

        @Override
        public Optional<GameSessionConversationTurn> findOwned(UUID id, UUID session, String username) {
            if (turnId.equals(id) && sessionId.equals(session) && owner.equals(username)) {
                return Optional.of(org.mockito.Mockito.mock(GameSessionConversationTurn.class));
            }
            return Optional.empty();
        }
    }

    private static final class CapturingFeedback implements AnswerFeedbackRepository {
        private final UUID persistedId;
        private AnswerFeedback saved;

        private CapturingFeedback(UUID persistedId) {
            this.persistedId = persistedId;
        }

        @Override
        public UUID save(AnswerFeedback feedback) {
            saved = feedback;
            return persistedId;
        }
    }
}
