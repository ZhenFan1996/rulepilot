CREATE TABLE answer_feedback (
    id UUID PRIMARY KEY,
    conversation_turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    game_session_id UUID NOT NULL REFERENCES game_session(id) ON DELETE CASCADE,
    rating VARCHAR(20) NOT NULL CHECK (rating IN ('HELPFUL', 'UNCLEAR', 'INCORRECT')),
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_turn_id, created_by)
);

CREATE INDEX ix_answer_feedback_session_created
    ON answer_feedback (game_session_id, created_at DESC);
