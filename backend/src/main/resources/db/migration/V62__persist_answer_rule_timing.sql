CREATE TABLE game_session_turn_rule_timing (
    turn_id UUID NOT NULL,
    position INTEGER NOT NULL,
    timing_context VARCHAR(500) NOT NULL,
    resolution_order VARCHAR(700) NOT NULL,
    order_source VARCHAR(400) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT fk_game_session_turn_rule_timing_turn
        FOREIGN KEY (turn_id) REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE
);
