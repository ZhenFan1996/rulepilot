ALTER TABLE outbox_event
    ADD COLUMN trace_parent VARCHAR(55),
    ADD COLUMN trace_state VARCHAR(512);

ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_trace_parent
        CHECK (
            trace_parent IS NULL
            OR (
                trace_parent COLLATE "C" ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
                AND substring(trace_parent FROM 4 FOR 32) <> repeat('0', 32)
                AND substring(trace_parent FROM 37 FOR 16) <> repeat('0', 16)
            )
        ),
    ADD CONSTRAINT ck_outbox_event_trace_state
        CHECK (
            trace_state IS NULL
            OR (
                trace_parent IS NOT NULL
                AND octet_length(trace_state) <= 512
                AND trace_state COLLATE "C" !~ '[^ -~]'
            )
        );
