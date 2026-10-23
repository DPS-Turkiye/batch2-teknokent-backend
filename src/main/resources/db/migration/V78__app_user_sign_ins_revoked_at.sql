-- When an admin last ended all of a user's sign-ins; sessions started before it are refused.
ALTER TABLE app_user ADD COLUMN sign_ins_revoked_at TIMESTAMPTZ;
