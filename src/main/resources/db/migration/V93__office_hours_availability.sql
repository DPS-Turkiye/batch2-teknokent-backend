-- An academic's office hours, the same way a mentor's availability works: weekly ranges with a slot length,
-- dates they are away, and the meeting defaults. They belong to the person, as a mentor's do, and apply to
-- every course they teach.
CREATE TABLE office_hours_settings (
    instructor_id UUID PRIMARY KEY REFERENCES office_hours_user(id) ON DELETE CASCADE,
    student_capacity INTEGER,
    default_meeting_duration_minutes INTEGER NOT NULL DEFAULT 45,
    meeting_buffer_minutes INTEGER NOT NULL DEFAULT 15,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_office_hours_settings_capacity CHECK (student_capacity IS NULL OR student_capacity BETWEEN 1 AND 50),
    CONSTRAINT chk_office_hours_settings_duration CHECK (default_meeting_duration_minutes BETWEEN 15 AND 480),
    CONSTRAINT chk_office_hours_settings_buffer CHECK (meeting_buffer_minutes BETWEEN 0 AND 240)
);

CREATE TABLE office_hours_rule (
    id UUID PRIMARY KEY,
    instructor_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    slot_minutes INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_office_hours_rule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_office_hours_rule_range CHECK (end_time > start_time),
    -- Same bounds the API takes (15-480 minutes); a zero length would never end the slot loop.
    CONSTRAINT chk_office_hours_rule_slot_minutes CHECK (slot_minutes BETWEEN 15 AND 480),
    CONSTRAINT uq_office_hours_rule_start UNIQUE (instructor_id, day_of_week, start_time)
);

CREATE TABLE office_hours_exception (
    id UUID PRIMARY KEY,
    instructor_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    reason VARCHAR(200),
    CONSTRAINT chk_office_hours_exception_range CHECK (ends_on >= starts_on),
    CONSTRAINT uq_office_hours_exception UNIQUE (instructor_id, starts_on, ends_on)
);
