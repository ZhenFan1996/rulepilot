alter table illustrated_lesson_step
    add column if not exists rule_facts_json text not null default '[]';
