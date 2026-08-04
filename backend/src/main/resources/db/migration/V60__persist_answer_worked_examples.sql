CREATE TABLE game_session_turn_worked_example (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    setup_text VARCHAR(500) NOT NULL,
    action_text VARCHAR(700) NOT NULL,
    outcome_text VARCHAR(500) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position)
);
