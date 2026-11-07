-- İŞKUR-style programs: only people whose address matches the program's domain and, when roster_only is on,
-- who are on the coordinator's list may join. individual_mentees gives each mentee a one-person team at sign-up.
-- All three are off by default, so existing programs are unchanged.
ALTER TABLE program ADD COLUMN eligible_email_domain VARCHAR(255);
ALTER TABLE program ADD COLUMN roster_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE program ADD COLUMN individual_mentees BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE program_eligible_email (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    added_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_program_eligible_email UNIQUE (program_id, email)
);
