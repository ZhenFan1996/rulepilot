ALTER TABLE game_session_conversation_turn
    ALTER COLUMN question TYPE TEXT,
    ALTER COLUMN short_verdict TYPE TEXT,
    ALTER COLUMN explanation TYPE TEXT,
    ALTER COLUMN clarification TYPE TEXT;

ALTER TABLE game_session_turn_citation
    ALTER COLUMN heading TYPE TEXT;

ALTER TABLE game_session_turn_exception
    ALTER COLUMN exception_text TYPE TEXT;

ALTER TABLE game_session_turn_situation_check
    ALTER COLUMN requirement TYPE TEXT,
    ALTER COLUMN player_fact TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_walkthrough_step
    ALTER COLUMN instruction TYPE TEXT,
    ALTER COLUMN explanation TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_decision_branch
    ALTER COLUMN condition_text TYPE TEXT,
    ALTER COLUMN outcome_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_exception_clause
    ALTER COLUMN condition_text TYPE TEXT,
    ALTER COLUMN effect_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_term_definition
    ALTER COLUMN term_text TYPE TEXT,
    ALTER COLUMN definition_text TYPE TEXT,
    ALTER COLUMN boundary_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_worked_example
    ALTER COLUMN setup_text TYPE TEXT,
    ALTER COLUMN action_text TYPE TEXT,
    ALTER COLUMN outcome_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_rule_priority
    ALTER COLUMN base_rule TYPE TEXT,
    ALTER COLUMN competing_rule TYPE TEXT,
    ALTER COLUMN resolution_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_rule_timing
    ALTER COLUMN timing_context TYPE TEXT,
    ALTER COLUMN resolution_order TYPE TEXT,
    ALTER COLUMN order_source TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_rule_tie
    ALTER COLUMN tie_context TYPE TEXT,
    ALTER COLUMN resolution_steps TYPE TEXT,
    ALTER COLUMN final_outcome TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_rule_scope
    ALTER COLUMN rule_context TYPE TEXT,
    ALTER COLUMN governing_condition TYPE TEXT,
    ALTER COLUMN current_situation TYPE TEXT,
    ALTER COLUMN effect_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_concept_comparison
    ALTER COLUMN left_concept TYPE TEXT,
    ALTER COLUMN left_definition TYPE TEXT,
    ALTER COLUMN right_concept TYPE TEXT,
    ALTER COLUMN right_definition TYPE TEXT,
    ALTER COLUMN common_ground TYPE TEXT,
    ALTER COLUMN key_difference TYPE TEXT,
    ALTER COLUMN practical_boundary TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;

ALTER TABLE game_session_turn_rule_option
    ALTER COLUMN decision_context TYPE TEXT,
    ALTER COLUMN selection_rule TYPE TEXT,
    ALTER COLUMN option_name TYPE TEXT,
    ALTER COLUMN availability_condition TYPE TEXT,
    ALTER COLUMN result_text TYPE TEXT,
    ALTER COLUMN citation_ids TYPE TEXT;
