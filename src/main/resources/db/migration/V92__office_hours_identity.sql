-- Office hours (ODTÜ, opened from Moodle through LTI 1.3): its own people, courses and enrolments, kept apart
-- from Menton's Firebase accounts and programs. A person and a course are known by the Moodle site that
-- launched them (issuer) plus Moodle's own id, so a second Moodle site can never collide with the first.
CREATE TABLE office_hours_user (
    id UUID PRIMARY KEY,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_launch_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_office_hours_user_subject UNIQUE (issuer, subject)
);

CREATE TABLE office_hours_course (
    id UUID PRIMARY KEY,
    issuer VARCHAR(255) NOT NULL,
    context_id VARCHAR(255) NOT NULL,
    short_name VARCHAR(255),
    title VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_office_hours_course_context UNIQUE (issuer, context_id)
);

-- The role Moodle last gave the person in the course; instructors set up office hours, students book them.
CREATE TABLE office_hours_enrollment (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES office_hours_user(id) ON DELETE CASCADE,
    course_id UUID NOT NULL REFERENCES office_hours_course(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_office_hours_enrollment UNIQUE (user_id, course_id),
    CONSTRAINT chk_office_hours_enrollment_role CHECK (role IN ('INSTRUCTOR', 'STUDENT'))
);
CREATE INDEX idx_office_hours_enrollment_course ON office_hours_enrollment (course_id);
