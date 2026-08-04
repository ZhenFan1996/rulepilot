CREATE TABLE game_session_turn_rule_tie (
    turn_id UUID NOT NULL,
    position INTEGER NOT NULL,
    tie_context VARCHAR(500) NOT NULL,
    resolution_steps VARCHAR(3100) NOT NULL,
    final_outcome VARCHAR(500) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT fk_game_session_turn_rule_tie_turn
        FOREIGN KEY (turn_id) REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE
);
