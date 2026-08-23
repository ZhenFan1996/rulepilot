ALTER TABLE recommendation_conversation
    ADD COLUMN active_claim_attempt_id UUID;

UPDATE recommendation_conversation
SET active_claim_attempt_id = gen_random_uuid()
WHERE active_client_turn_id IS NOT NULL;

-- Keep the V79 active-turn check unchanged during the rollback window. The previous
-- application version neither writes this token on claim nor clears it on completion or release.
