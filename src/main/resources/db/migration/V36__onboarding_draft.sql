-- Unfinished founder/mentor wizards used to live in the browser's
-- sessionStorage. That meant a refresh on another device started from scratch,
-- and the teacher asked for this state to sit on the server instead.
--
-- Keyed by firebase_uid rather than app_user.id because the founder wizard
-- runs after Firebase signup and before registerFounder creates the row.
CREATE TABLE onboarding_draft (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    kind         VARCHAR(64)  NOT NULL,
    step         VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT onboarding_draft_kind_check
        CHECK (kind IN ('signup-wizard', 'mentor-onboarding'))
);
