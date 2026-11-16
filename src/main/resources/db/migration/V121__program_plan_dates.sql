-- The weekly plan keeps the dates its syllabus prints. Until now a week's dates were only computed from the
-- program's start (week N = start + 7(N-1)), so a program whose start differed from the file's shifted every week,
-- and a file with dated sessions or event days (a bootcamp, a demo day) lost them.
-- A week's start_date/end_date are the printed ones (both or neither); without them the week still follows the
-- program calendar. Attendance stays keyed by week_number: the week whose stored dates contain today is the running
-- week, so check-ins and the plan name the same week.
ALTER TABLE program_week
    ADD COLUMN start_date DATE,
    ADD COLUMN end_date   DATE,
    ADD CONSTRAINT chk_program_week_dates CHECK (
        (start_date IS NULL AND end_date IS NULL)
        OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date));

-- A week's sessions on their own days (a program that meets Monday, Wednesday and Friday), in the file's order.
-- Saved and replaced with their week.
CREATE TABLE program_week_session (
    program_week_id UUID NOT NULL REFERENCES program_week (id) ON DELETE CASCADE,
    position        INTEGER NOT NULL,
    session_date    DATE NOT NULL,
    start_time      TIME,
    end_time        TIME,
    place           VARCHAR(160),
    title           VARCHAR(160),
    PRIMARY KEY (program_week_id, position)
);

-- Days that are an event of their own rather than part of a week: a bootcamp, a rehearsal, a demo day.
-- Saved and replaced with the weekly plan. `details` holds short lines, one per line.
CREATE TABLE program_special_day (
    id          UUID PRIMARY KEY,
    program_id  UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    event_date  DATE NOT NULL,
    start_time  TIME,
    end_time    TIME,
    place       VARCHAR(160),
    title       VARCHAR(160) NOT NULL,
    details     TEXT NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  UUID REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_program_special_day_program_date ON program_special_day (program_id, event_date);
