CREATE TABLE game_session_turn_rule_priority (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    base_rule VARCHAR(500) NOT NULL,
    competing_rule VARCHAR(500) NOT NULL,
    resolution_text VARCHAR(600) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
