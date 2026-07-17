CREATE TABLE game (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_game_name ON game (lower(name));

CREATE TABLE game_edition (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    language VARCHAR(20) NOT NULL,
    publication_year INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_game_edition_year CHECK (publication_year IS NULL OR publication_year BETWEEN 1900 AND 2200)
);

CREATE UNIQUE INDEX uk_game_edition_name_language ON game_edition (game_id, lower(name), lower(language));

CREATE TABLE expansion (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_expansion_name ON expansion (game_id, lower(name));

CREATE TABLE edition_expansion (
    edition_id UUID NOT NULL REFERENCES game_edition(id) ON DELETE CASCADE,
    expansion_id UUID NOT NULL REFERENCES expansion(id) ON DELETE CASCADE,
    PRIMARY KEY (edition_id, expansion_id)
);
