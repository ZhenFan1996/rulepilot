CREATE TABLE game_session_turn_term_definition (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    term_text VARCHAR(120) NOT NULL,
    definition_text VARCHAR(600) NOT NULL,
    boundary_text VARCHAR(400) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
