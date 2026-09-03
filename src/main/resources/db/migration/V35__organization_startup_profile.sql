-- The signup wizard's second step collects a startup's website, problem,
-- solution, target user, sector, maturity and support areas, and the review step
-- displays all of them back to the founder before submit. None of it was stored:
-- registerFounder sent only the organization name and description, so everything
-- else was collected and discarded. This is the same defect V33 fixed for the
-- person (bio, LinkedIn, expertise), one level up at the startup.
--
-- These are typed columns on organization rather than keys inside
-- profile_analysis.confirmed_profile. That blob is the *parser's* output and is
-- what MentorRecommendationService reads to build its request; writing hand-typed
-- form values into it would make the signup form a producer for the matching
-- pipeline. Keeping the two apart preserves the distinction between what the
-- founder claimed and what the AI extracted from evidence, and it keeps the
-- parse's "suggest, never overwrite" contract intact.
ALTER TABLE organization
    ADD COLUMN website          VARCHAR(255),
    ADD COLUMN problem          TEXT,
    ADD COLUMN solution         TEXT,
    ADD COLUMN current_solution TEXT,
    ADD COLUMN target_user      TEXT,
    ADD COLUMN sector           VARCHAR(255),
    ADD COLUMN maturity         VARCHAR(255),
    ADD COLUMN program_level    VARCHAR(255);

-- Mirrors app_user_expertise: an ordered element collection, so the order the
-- founder arranged their support areas in survives a round trip.
CREATE TABLE organization_support_area (
    organization_id UUID         NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    display_order   INTEGER      NOT NULL,
    support_area    VARCHAR(255) NOT NULL,
    PRIMARY KEY (organization_id, display_order)
);

CREATE INDEX idx_organization_support_area_organization
    ON organization_support_area (organization_id);
