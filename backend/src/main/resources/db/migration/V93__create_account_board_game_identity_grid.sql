CREATE TABLE account_board_game_identity (
    username VARCHAR(40) NOT NULL REFERENCES app_user (username) ON DELETE CASCADE,
    slot VARCHAR(32) NOT NULL CHECK (slot IN (
        'FAVORITE_GAME',
        'FAVORITE_ART',
        'FAVORITE_DESIGNER',
        'FAVORITE_MECHANISM',
        'FAVORITE_THEME',
        'FAVORITE_PUBLISHER',
        'FAVORITE_EXPANSION',
        'FAVORITE_COMPONENT',
        'WISHLIST_GAME')),
    bgg_id INTEGER NOT NULL CHECK (bgg_id > 0),
    game_name TEXT NOT NULL CHECK (game_name <> ''),
    chinese_name TEXT NOT NULL DEFAULT '',
    thumbnail_url TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (username, slot)
);

CREATE INDEX ix_account_board_game_identity_bgg
    ON account_board_game_identity (bgg_id);
