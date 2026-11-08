-- Students ask an academic for office hours the way a mentee asks a mentor for a meeting: a request with a short
-- note and up to three times, which the academic accepts, declines, answers with other times, or questions.
-- An accepted request becomes a meeting. Times are kept as "yyyy-MM-ddTHH:mm" in Turkey time, as for mentors.
CREATE TABLE office_hours_request (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES office_hours_course(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    instructor_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    topic TEXT NOT NULL,
    expected_outcome TEXT,
    preferred_slots VARCHAR(100) NOT NULL,
    proposed_slots VARCHAR(100),
    instructor_message TEXT,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_office_hours_request_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'DECLINED', 'MORE_INFO_REQUESTED', 'COUNTER_PROPOSED', 'WITHDRAWN'))
);
CREATE INDEX idx_office_hours_request_course_student ON office_hours_request (course_id, student_id);
CREATE INDEX idx_office_hours_request_instructor ON office_hours_request (instructor_id, status);

-- The academic's questions and the student's answers on a request.
CREATE TABLE office_hours_request_message (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES office_hours_request(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_office_hours_request_message_type CHECK (type IN ('INSTRUCTOR_QUESTION', 'STUDENT_REPLY'))
);
CREATE INDEX idx_office_hours_request_message_request ON office_hours_request_message (request_id, created_at);

CREATE TABLE office_hours_meeting (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE REFERENCES office_hours_request(id) ON DELETE CASCADE,
    course_id UUID NOT NULL REFERENCES office_hours_course(id) ON DELETE CASCADE,
    instructor_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- One academic cannot be booked twice at the same moment.
CREATE UNIQUE INDEX uq_office_hours_meeting_instructor_time ON office_hours_meeting (instructor_id, scheduled_at);
CREATE INDEX idx_office_hours_meeting_course ON office_hours_meeting (course_id, scheduled_at);
