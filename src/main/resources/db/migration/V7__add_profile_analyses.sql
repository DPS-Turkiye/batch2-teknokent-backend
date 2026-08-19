CREATE TABLE profile_analysis (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    source_filename VARCHAR(255) NOT NULL,
    profile_type VARCHAR(40) NOT NULL,
    parsed_profile TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_profile_analysis_organization_created
    ON profile_analysis(organization_id, created_at DESC);
