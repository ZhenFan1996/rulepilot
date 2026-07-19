CREATE TABLE app_user (
    username VARCHAR(40) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user_authority (
    username VARCHAR(40) NOT NULL REFERENCES app_user (username) ON DELETE CASCADE,
    authority VARCHAR(80) NOT NULL,
    PRIMARY KEY (username, authority)
);
