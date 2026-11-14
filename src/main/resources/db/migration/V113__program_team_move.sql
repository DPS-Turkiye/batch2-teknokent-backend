-- An admin can move a team from one program to another. The team's row leaves program_startup for the old program
-- and its facts are kept here: which team, from which program to which, who moved it and when, and since when it
-- had been in the old program. This is both the audit of the move and the record that the team used to be there.
-- Ids only and no foreign keys, as in user_deletion_audit, so a row outlives the programs, team and admin it names.
CREATE TABLE program_team_move (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    from_program_id UUID        NOT NULL,
    to_program_id   UUID        NOT NULL,
    moved_by_id     UUID        NOT NULL,
    moved_at        TIMESTAMPTZ NOT NULL,
    joined_from_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_program_team_move_programs CHECK (from_program_id <> to_program_id)
);

-- A team's own history, newest first, and the teams that left a program.
CREATE INDEX idx_program_team_move_organization ON program_team_move (organization_id, moved_at);
CREATE INDEX idx_program_team_move_from_program ON program_team_move (from_program_id, moved_at);
