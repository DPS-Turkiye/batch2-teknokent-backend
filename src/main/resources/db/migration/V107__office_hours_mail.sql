-- Office-hours people get e-mail instead of in-app notifications: the language Moodle launched them in, read the
-- way app_user.preferred_language is, and a mark per side of each meeting, so each side's reminder goes out once
-- and a failed one can be sent again without repeating the other side's.
ALTER TABLE office_hours_user ADD COLUMN preferred_language VARCHAR(8) NOT NULL DEFAULT 'TR'
    CONSTRAINT chk_office_hours_user_language CHECK (preferred_language IN ('TR', 'EN'));

ALTER TABLE office_hours_meeting
    ADD COLUMN student_reminded_at TIMESTAMPTZ,
    ADD COLUMN instructor_reminded_at TIMESTAMPTZ;
CREATE INDEX idx_office_hours_meeting_reminder_due ON office_hours_meeting (scheduled_at)
    WHERE student_reminded_at IS NULL OR instructor_reminded_at IS NULL;

-- E-mails about a request wait here until they are sent: written in the same transaction as the change, so a
-- rolled-back change sends nothing, and kept until SES takes them, so a failed send is tried again later. A row is
-- deleted once sent or given up on, so addresses and topics do not pile up.
CREATE TABLE office_hours_mail_outbox (
    id UUID PRIMARY KEY,
    to_address VARCHAR(320) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_office_hours_mail_outbox_due ON office_hours_mail_outbox (next_attempt_at);
