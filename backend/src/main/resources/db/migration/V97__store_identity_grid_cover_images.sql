ALTER TABLE account_board_game_identity
    ADD COLUMN image_url TEXT NOT NULL DEFAULT '';

UPDATE account_board_game_identity identity
SET image_url = COALESCE(
        (SELECT NULLIF(cache.payload->>'imageUrl', '')
         FROM bgg_metadata_cache cache
         WHERE cache.cache_kind = 'DISCOVERY' AND cache.bgg_id = identity.bgg_id),
        (SELECT NULLIF(cache.payload->>'imageUrl', '')
         FROM bgg_metadata_cache cache
         WHERE cache.cache_kind = 'GAME' AND cache.bgg_id = identity.bgg_id),
        (SELECT NULLIF(cache.payload->>'thumbnailUrl', '')
         FROM bgg_metadata_cache cache
         WHERE cache.cache_kind = 'DISCOVERY' AND cache.bgg_id = identity.bgg_id),
        (SELECT NULLIF(cache.payload->>'thumbnailUrl', '')
         FROM bgg_metadata_cache cache
         WHERE cache.cache_kind = 'GAME' AND cache.bgg_id = identity.bgg_id),
        identity.thumbnail_url);
