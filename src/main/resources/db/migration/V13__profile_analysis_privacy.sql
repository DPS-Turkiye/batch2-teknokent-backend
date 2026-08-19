ALTER TABLE profile_analysis
    ADD COLUMN consent_accepted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN redaction_summary TEXT NOT NULL DEFAULT '{}';
