ALTER TABLE mentor_profile
    ADD COLUMN about TEXT NOT NULL DEFAULT '',
    ADD COLUMN default_meeting_duration_minutes SMALLINT NOT NULL DEFAULT 45,
    ADD COLUMN meeting_buffer_minutes SMALLINT NOT NULL DEFAULT 15,
    ADD CONSTRAINT chk_mentor_profile_default_meeting_duration
        CHECK (default_meeting_duration_minutes BETWEEN 15 AND 480),
    ADD CONSTRAINT chk_mentor_profile_meeting_buffer
        CHECK (meeting_buffer_minutes BETWEEN 0 AND 240);

CREATE TABLE mentor_availability_exception (
    id UUID PRIMARY KEY,
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    reason VARCHAR(200),
    CONSTRAINT chk_mentor_availability_exception_dates CHECK (ends_on >= starts_on),
    CONSTRAINT uq_mentor_availability_exception UNIQUE (mentor_id, starts_on, ends_on)
);

CREATE INDEX idx_mentor_availability_exception_mentor_dates
    ON mentor_availability_exception(mentor_id, starts_on, ends_on);
