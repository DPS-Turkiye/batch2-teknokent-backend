-- The language notifications and e-mails are written in for this user; the frontend saves it on sign-in and on switch.
ALTER TABLE app_user ADD COLUMN preferred_language VARCHAR(8) NOT NULL DEFAULT 'TR';
