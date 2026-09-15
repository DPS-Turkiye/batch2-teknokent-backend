ALTER TABLE organization ADD COLUMN funding_stage VARCHAR(64);
ALTER TABLE organization ADD CONSTRAINT chk_organization_funding_stage
    CHECK (funding_stage IS NULL OR funding_stage IN (
        'Kendi kaynaklarıyla', 'Pre-seed', 'Seed', 'Seri A', 'Seri B ve sonrası'
    ));

ALTER TABLE organization ADD COLUMN founded_year INTEGER;
ALTER TABLE organization ADD CONSTRAINT chk_organization_founded_year
    CHECK (founded_year IS NULL OR founded_year BETWEEN 1900 AND 2100);
