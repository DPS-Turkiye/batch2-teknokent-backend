-- Signup collects a bio, a LinkedIn URL and expertise areas, and the CV parser
-- now produces all three, but registerFounder had nowhere to put them: app_user
-- carried only job_title. They were collected and discarded on submit.
--
-- These live on app_user rather than a mentee-only table because they describe
-- the person, not their role. Mentors keep their own mentor_profile specialties;
-- unifying the two is a larger change and deliberately not attempted here.
ALTER TABLE app_user
    ADD COLUMN bio TEXT,
    ADD COLUMN linkedin_url VARCHAR(255);

-- Mirrors mentor_specialty: an ordered element collection, so the order the user
-- arranged their expertise in survives a round trip.
CREATE TABLE app_user_expertise (
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    display_order INTEGER      NOT NULL,
    expertise     VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, display_order)
);

CREATE INDEX idx_app_user_expertise_user ON app_user_expertise (user_id);
