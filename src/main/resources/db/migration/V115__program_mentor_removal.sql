-- An admin can take an accepted mentor out of a program. The mentor's program_mentor row is deleted, so its facts
-- are kept here: which mentor, from which program, who removed them and when, and since when they had been in the
-- program. This is both the audit of the removal and the record that the mentor used to be there.
-- Ids only and no foreign keys, as in program_team_move, so a row outlives the program, mentor and admin it names.
-- V114 is left free for a change that was numbered before this one.
CREATE TABLE program_mentor_removal (
    id            UUID PRIMARY KEY,
    mentor_id     UUID        NOT NULL,
    program_id    UUID        NOT NULL,
    removed_by_id UUID        NOT NULL,
    removed_at    TIMESTAMPTZ NOT NULL,
    joined_at     TIMESTAMPTZ NOT NULL
);

-- A mentor's own history, newest first, and the mentors that left a program.
CREATE INDEX idx_program_mentor_removal_mentor ON program_mentor_removal (mentor_id, removed_at);
CREATE INDEX idx_program_mentor_removal_program ON program_mentor_removal (program_id, removed_at);
