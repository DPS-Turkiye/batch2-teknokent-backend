-- An İŞKUR student's unfinished peer-mentor wizard, kept on the server so it survives a refresh or another device.
-- One per student; publishing the profile removes it. The payload holds the wizard's own answers and nothing else.
CREATE TABLE peer_mentor_profile_draft (
    user_id    UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    payload    JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
