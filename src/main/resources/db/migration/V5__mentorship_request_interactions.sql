CREATE TABLE mentorship_request_message (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES mentorship_request(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id),
    message_type VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentorship_request_message_type
        CHECK (message_type IN ('MENTOR_QUESTION', 'MENTEE_REPLY')),
    CONSTRAINT chk_mentorship_request_message_body
        CHECK (BTRIM(body) <> '')
);

CREATE INDEX idx_mentorship_request_message_request_created
    ON mentorship_request_message(request_id, created_at);

CREATE TABLE mentor_availability_slot (
    id UUID PRIMARY KEY,
    mentor_id UUID NOT NULL REFERENCES app_user(id),
    starts_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentor_availability_slot_duration
        CHECK (duration_minutes > 0),
    CONSTRAINT uq_mentor_availability_slot_start
        UNIQUE (mentor_id, starts_at)
);

CREATE INDEX idx_mentor_availability_slot_mentor_starts
    ON mentor_availability_slot(mentor_id, starts_at);

INSERT INTO mentor_availability_slot (id, mentor_id, starts_at) VALUES
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', (CURRENT_DATE + 1 + TIME '09:00') AT TIME ZONE 'Europe/Istanbul'),
    ('30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', (CURRENT_DATE + 1 + TIME '10:30') AT TIME ZONE 'Europe/Istanbul'),
    ('30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', (CURRENT_DATE + 2 + TIME '14:00') AT TIME ZONE 'Europe/Istanbul'),
    ('30000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', (CURRENT_DATE + 3 + TIME '11:00') AT TIME ZONE 'Europe/Istanbul'),
    ('30000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000002', (CURRENT_DATE + 4 + TIME '15:00') AT TIME ZONE 'Europe/Istanbul');
