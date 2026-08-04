CREATE TABLE game_session_turn_decision_branch (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    condition_text VARCHAR(300) NOT NULL,
    outcome_text VARCHAR(500) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT chk_game_session_turn_decision_branch_basis
        CHECK (basis IN ('EXPLICIT_RULE', 'RULEBOOK_EXAMPLE'))
);
