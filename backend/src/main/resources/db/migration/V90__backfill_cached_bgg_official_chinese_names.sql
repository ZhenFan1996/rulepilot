INSERT INTO bgg_game_name_alias (bgg_id, alias, locale, source, observed_at)
SELECT bgg_id,
       btrim(payload ->> 'chineseName') AS alias,
       'zh' AS locale,
       'BGG_OFFICIAL_VERSION' AS source,
       cached_at AS observed_at
FROM bgg_metadata_cache
WHERE cache_kind = 'DISCOVERY'
  AND NULLIF(btrim(payload ->> 'chineseName'), '') IS NOT NULL
ON CONFLICT (bgg_id, alias, locale) DO UPDATE SET
    source = EXCLUDED.source,
    observed_at = GREATEST(bgg_game_name_alias.observed_at, EXCLUDED.observed_at);

INSERT INTO bgg_game_name_alias (bgg_id, alias, locale, source, observed_at)
SELECT bgg_id,
       btrim(name.value) AS alias,
       'zh' AS locale,
       'BGG_OFFICIAL_VERSION' AS source,
       MAX(cached_at) AS observed_at
FROM bgg_metadata_cache
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE
        WHEN jsonb_typeof(payload -> 'officialChineseNames') = 'array'
            THEN payload -> 'officialChineseNames'
        ELSE '[]'::jsonb
    END
) AS name(value)
WHERE cache_kind = 'GAME'
  AND NULLIF(btrim(name.value), '') IS NOT NULL
GROUP BY bgg_id, btrim(name.value)
ON CONFLICT (bgg_id, alias, locale) DO UPDATE SET
    source = EXCLUDED.source,
    observed_at = GREATEST(bgg_game_name_alias.observed_at, EXCLUDED.observed_at);
