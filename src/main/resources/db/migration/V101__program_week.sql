-- A program's weekly plan, worked out from its syllabus (AI proposal, then reviewed and saved by a coordinator).
-- One row per program week; the week's dates are not stored but come from the program's start and end dates
-- (ProgramCalendar), so moving the program never leaves the plan out of step. Topics, activities and deliverables
-- are short lines, one per line. `inferred` marks a week the AI spread out from a longer phase rather than read
-- from a week-by-week source.
CREATE TABLE program_week (
    id            UUID PRIMARY KEY,
    program_id    UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    week_number   INTEGER NOT NULL,
    title         VARCHAR(160) NOT NULL,
    topics        TEXT NOT NULL DEFAULT '',
    activities    TEXT NOT NULL DEFAULT '',
    deliverables  TEXT NOT NULL DEFAULT '',
    inferred      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ NOT NULL,
    updated_by    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_program_week UNIQUE (program_id, week_number),
    CONSTRAINT chk_program_week_number CHECK (week_number BETWEEN 1 AND 104)
);
