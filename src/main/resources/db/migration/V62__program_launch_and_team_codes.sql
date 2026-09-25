-- Rules set when a program is launched (flow 03.3) and editable later in Program Ayarları.
ALTER TABLE program
    ADD COLUMN default_mentor_capacity INTEGER NOT NULL DEFAULT 4,
    ADD COLUMN communication_gap_days INTEGER NOT NULL DEFAULT 14,
    ADD COLUMN bottleneck_flag_days INTEGER NOT NULL DEFAULT 21,
    ADD CONSTRAINT chk_program_default_mentor_capacity CHECK (default_mentor_capacity BETWEEN 1 AND 20),
    ADD CONSTRAINT chk_program_communication_gap_days CHECK (communication_gap_days BETWEEN 1 AND 180),
    ADD CONSTRAINT chk_program_bottleneck_flag_days CHECK (bottleneck_flag_days BETWEEN 1 AND 365);

-- A team the coordinator put into a program before it had an account. The lead signs up with the code;
-- from then on organization_id is set and the code is also the organization's invite code for members.
CREATE TABLE program_team_invite (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    team_name VARCHAR(255) NOT NULL,
    lead_email VARCHAR(255) NOT NULL,
    code VARCHAR(32) NOT NULL,
    organization_id UUID REFERENCES organization(id),
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    joined_at TIMESTAMPTZ,
    CONSTRAINT uq_program_team_invite_code UNIQUE (code)
);

CREATE UNIQUE INDEX uq_program_team_invite_lead ON program_team_invite (program_id, lower(lead_email));
