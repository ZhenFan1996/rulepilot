CREATE TABLE game_session_turn_walkthrough_step (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    instruction VARCHAR(240) NOT NULL,
    explanation VARCHAR(500) NOT NULL,
    order_basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT chk_game_session_turn_walkthrough_order_basis
        CHECK (order_basis IN ('RULE_ORDER', 'EXPLANATION_ORDER'))
);
