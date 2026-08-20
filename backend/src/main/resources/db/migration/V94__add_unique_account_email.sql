ALTER TABLE app_user
    ADD COLUMN email VARCHAR(254);

CREATE UNIQUE INDEX uq_app_user_normalized_email
    ON app_user (lower(email))
    WHERE email IS NOT NULL;
