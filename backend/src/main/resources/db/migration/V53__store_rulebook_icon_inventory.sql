ALTER TABLE visual_rulebook_page_fact
    ADD COLUMN icon_occurrences TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN icon_inventory_complete BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN visual_rulebook_page_fact.icon_occurrences IS
    'Versioned page-local gameplay icon occurrences with tight source-page regions and explicit meaning evidence.';

COMMENT ON COLUMN visual_rulebook_page_fact.icon_inventory_complete IS
    'True only when the vision provider reports that the complete rendered page was checked for gameplay icons.';
