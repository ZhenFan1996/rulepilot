DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_user WHERE username = 'public-reader') THEN
        RAISE EXCEPTION 'public-reader already exists; inspect identity ownership before migration';
    END IF;
    INSERT INTO app_user (username, password_hash, enabled)
    VALUES ('public-reader', '!disabled-system-identity', FALSE);
END $$;
-- No authority is granted. The existing registration path refuses an existing username.
