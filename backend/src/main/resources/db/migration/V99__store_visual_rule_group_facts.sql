ALTER TABLE visual_rulebook_page_fact
    ADD COLUMN rule_group_facts TEXT NOT NULL DEFAULT '[]';

COMMENT ON COLUMN visual_rulebook_page_fact.rule_group_facts IS
    'Typed JSON rule-group facts. Schema 36+ coverage must use this field and never parse factual_summary.';
