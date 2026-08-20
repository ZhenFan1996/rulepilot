ALTER TABLE recommendation_conversation
    DROP CONSTRAINT uq_recommendation_conversation_owner;

CREATE INDEX idx_recommendation_conversation_owner_updated
    ON recommendation_conversation (owner_username, updated_at DESC, id DESC);
