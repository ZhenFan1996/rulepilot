CREATE TABLE game_session_turn_concept_comparison (
    turn_id UUID NOT NULL,
    position INTEGER NOT NULL,
    left_concept VARCHAR(120) NOT NULL,
    left_definition VARCHAR(600) NOT NULL,
    right_concept VARCHAR(120) NOT NULL,
    right_definition VARCHAR(600) NOT NULL,
    common_ground VARCHAR(500) NOT NULL,
    key_difference VARCHAR(700) NOT NULL,
    practical_boundary VARCHAR(600) NOT NULL,
    basis VARCHAR(40) NOT NULL,
    citation_ids VARCHAR(110) NOT NULL,
    PRIMARY KEY (turn_id, position),
    CONSTRAINT fk_game_session_turn_concept_comparison_turn
        FOREIGN KEY (turn_id) REFERENCES game_session_conversation_turn(id) ON DELETE CASCADE
);
