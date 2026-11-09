-- Weekly attendance: a participant checks in for the program week that is running (weeks counted from the
-- program's start date, as ProgramCalendar does). One row per participant per week; checking in is the only write.
CREATE TABLE program_attendance (
    id             UUID PRIMARY KEY,
    program_id     UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    week_number    INTEGER NOT NULL,
    checked_in_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_program_attendance UNIQUE (program_id, user_id, week_number),
    CONSTRAINT chk_program_attendance_week CHECK (week_number >= 1)
);

CREATE INDEX idx_program_attendance_program_week ON program_attendance (program_id, week_number);
