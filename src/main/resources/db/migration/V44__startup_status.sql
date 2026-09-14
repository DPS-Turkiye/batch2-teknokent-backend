ALTER TABLE organization ADD COLUMN status_version VARCHAR(32);
ALTER TABLE organization ADD COLUMN status_summary TEXT;
ALTER TABLE organization ADD COLUMN completion_percent INTEGER;
ALTER TABLE organization ADD CONSTRAINT chk_organization_completion_percent
    CHECK (completion_percent IS NULL OR completion_percent BETWEEN 0 AND 100);
