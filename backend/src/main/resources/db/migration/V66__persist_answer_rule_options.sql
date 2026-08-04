CREATE TABLE game_session_turn_rule_option (
    turn_id UUID NOT NULL,
    position INTEGER NOT NULL,
    decision_context VARCHAR(240) NOT NULL,
    selection_rule VARCHAR(400) NOT NULL,
    option_name VARCHAR(160) NOT NULL,
    availability_condition VARCHAR(500) NOT NULL,
    result_text VARCHAR(700) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT fk_game_session_turn_rule_option_turn
        FOREIGN KEY (turn_id) REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE
);
