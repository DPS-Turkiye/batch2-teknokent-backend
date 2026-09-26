-- Flow 03.1: a coordinator invites a mentor who has no account yet. The coordinator copies the signup link
-- (nothing is emailed); it is valid for 7 days and can be renewed. Signing up with it sets mentor_id and
-- leaves a pending program_mentor row for the coordinator to verify.
CREATE TABLE program_mentor_invite (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    mentor_id UUID REFERENCES app_user(id),
    joined_at TIMESTAMPTZ,
    CONSTRAINT uq_program_mentor_invite_code UNIQUE (code)
);

CREATE UNIQUE INDEX uq_program_mentor_invite_email ON program_mentor_invite (program_id, lower(email));
