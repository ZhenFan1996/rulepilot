ALTER TABLE visual_rulebook_page_fact
    ADD COLUMN rule_group_identifiers TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN rule_group_inventory_complete BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN visual_rulebook_page_fact.rule_group_identifiers IS
    'JSON array of page-owned gameplay rule-group identifiers recorded by the complete teaching catalog.';

COMMENT ON COLUMN visual_rulebook_page_fact.rule_group_inventory_complete IS
    'True only when the teaching catalog inspected the whole rendered page and inventoried every readable gameplay rule group.';
