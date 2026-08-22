alter table rule_chunk
    add column if not exists content_kind varchar(48) not null default 'CANONICAL_TEXT';

update rule_chunk
set content_kind = 'VISUAL_PLACEHOLDER'
where content = 'This rulebook page is visual evidence. Text extraction was unavailable; inspect the rendered page image.';
