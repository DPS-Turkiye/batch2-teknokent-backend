CREATE INDEX idx_profile_analysis_confirmed_startup_latest
    ON profile_analysis (organization_id, created_at DESC)
    WHERE status = 'CONFIRMED' AND profile_type = 'mentee';
