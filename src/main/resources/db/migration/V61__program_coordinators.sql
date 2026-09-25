-- Coordinators who run a program alongside the one who created it. The creator stays in program.created_by
-- and is not repeated here.
CREATE TABLE program_coordinator (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id),
    added_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_program_coordinator UNIQUE (program_id, user_id)
);

CREATE INDEX idx_program_coordinator_user ON program_coordinator (user_id);
