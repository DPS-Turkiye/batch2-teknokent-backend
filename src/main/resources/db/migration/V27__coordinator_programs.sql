ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'COORDINATOR', 'MENTOR', 'MENTEE'));

INSERT INTO app_user (id, email, full_name, role, status)
VALUES (
    '00000000-0000-0000-0000-0000000000c1',
    'coordinator@teknokent.demo',
    'Demo Coordinator',
    'COORDINATOR',
    'APPROVED'
)
ON CONFLICT (email) DO NOTHING;

CREATE TABLE program (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE program_startup (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_program_startup UNIQUE (program_id, organization_id)
);

CREATE TABLE program_mentor (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    mentor_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(32) NOT NULL,
    invited_by VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at TIMESTAMPTZ,
    CONSTRAINT uq_program_mentor UNIQUE (program_id, mentor_id),
    CONSTRAINT chk_program_mentor_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_program_mentor_invited_by CHECK (invited_by IN ('COORDINATOR', 'MENTOR'))
);
