-- Program homework ("Ödev ver"): a coordinator gives one Ödev to every team of a program at once. The program-level
-- row keeps what was asked; each team gets its own action_item copy (process_assignment = TRUE) linked back to it,
-- so the team hands it in through the usual Süreç flow and the coordinator can see who did. A team that joins later
-- gets the program's Ödevler that are still open (no due date, or due today or later) the next time its Süreç or
-- the coordinator's list is read.
-- Fail fast instead of queueing the app behind a long-held lock during the deploy; the migration can be retried.
SET LOCAL lock_timeout = '5s';

CREATE TABLE program_assignment (
    id               UUID PRIMARY KEY,
    program_id       UUID         NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    due_date         DATE,
    program_phase_id UUID         REFERENCES program_phase (id) ON DELETE SET NULL,
    file_required    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_id    UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_program_assignment_program ON program_assignment (program_id, created_at DESC);

-- Every team's copy points at its program Ödev; plain action items and mentor Ödevler leave it null.
ALTER TABLE action_item ADD COLUMN program_assignment_id UUID REFERENCES program_assignment (id) ON DELETE SET NULL;
-- When the team handed the Ödev in, so a hand-in after the due date reads as late.
ALTER TABLE action_item ADD COLUMN submitted_at TIMESTAMPTZ;

-- One copy per team; NULLs (every other action item) never collide.
ALTER TABLE action_item ADD CONSTRAINT uq_action_item_program_assignment UNIQUE (program_assignment_id, organization_id);
