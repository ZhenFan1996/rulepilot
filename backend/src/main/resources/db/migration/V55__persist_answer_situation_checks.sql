CREATE TABLE game_session_turn_situation_check (
    turn_id UUID NOT NULL REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    requirement VARCHAR(240) NOT NULL,
    status VARCHAR(40) NOT NULL,
    player_fact VARCHAR(240) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT chk_game_session_turn_situation_check_status
        CHECK (status IN ('CONFIRMED', 'CONTRADICTED', 'NOT_PROVIDED'))
);
