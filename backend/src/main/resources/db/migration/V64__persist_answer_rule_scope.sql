CREATE TABLE game_session_turn_rule_scope (
    turn_id UUID NOT NULL,
    position INTEGER NOT NULL,
    rule_context VARCHAR(500) NOT NULL,
    governing_condition VARCHAR(500) NOT NULL,
    current_situation VARCHAR(300) NOT NULL,
    match_status VARCHAR(40) NOT NULL,
    effect_text VARCHAR(600) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT fk_game_session_turn_rule_scope_turn
        FOREIGN KEY (turn_id) REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE
);
