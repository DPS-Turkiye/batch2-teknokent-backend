ALTER TABLE profile_analysis
    ADD COLUMN confirmed_profile TEXT,
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE;
