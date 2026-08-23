CREATE INDEX ix_bgg_metadata_cache_text_search
    ON bgg_metadata_cache
    USING GIN (
        to_tsvector('english'::regconfig,
            coalesce(payload->>'name', '') || ' ' ||
            coalesce(payload->>'chineseName', '') || ' ' ||
            coalesce(payload->>'description', '') || ' ' ||
            coalesce(payload->>'categories', '') || ' ' ||
            coalesce(payload->>'mechanics', '') || ' ' ||
            coalesce(payload->>'families', '') || ' ' ||
            coalesce(payload->>'designers', '') || ' ' ||
            coalesce(payload->>'publishers', ''))
    )
    WHERE cache_kind IN ('DISCOVERY', 'GAME');
