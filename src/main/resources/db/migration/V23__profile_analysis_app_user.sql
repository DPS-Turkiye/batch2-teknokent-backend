ALTER TABLE profile_analysis
    ADD COLUMN app_user_id UUID REFERENCES app_user(id);

CREATE INDEX idx_profile_analysis_app_user_latest
    ON profile_analysis (app_user_id, created_at DESC)
    WHERE app_user_id IS NOT NULL;
