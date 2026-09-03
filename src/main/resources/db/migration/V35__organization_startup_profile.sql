-- Columns on organization, not keys inside profile_analysis.confirmed_profile:
-- that blob is the parser's output and feeds MentorRecommendationService, so
-- writing form values into it would make signup a producer for matching.
ALTER TABLE organization
    ADD COLUMN website          VARCHAR(255),
    ADD COLUMN problem          TEXT,
    ADD COLUMN solution         TEXT,
    ADD COLUMN current_solution TEXT,
    ADD COLUMN target_user      TEXT,
    ADD COLUMN sector           VARCHAR(255),
    ADD COLUMN maturity         VARCHAR(255),
    ADD COLUMN program_level    VARCHAR(255);

-- Mirrors app_user_expertise: an ordered collection so order survives a round trip.
CREATE TABLE organization_support_area (
    organization_id UUID         NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    display_order   INTEGER      NOT NULL,
    support_area    VARCHAR(255) NOT NULL,
    PRIMARY KEY (organization_id, display_order)
);

CREATE INDEX idx_organization_support_area_organization
    ON organization_support_area (organization_id);
