-- Office-hours people get e-mail instead of in-app notifications: the language Moodle launched them in, read the
-- way app_user.preferred_language is, and a mark per meeting so its reminder goes out once, as for mentors.
ALTER TABLE office_hours_user ADD COLUMN preferred_language VARCHAR(8) NOT NULL DEFAULT 'TR'
    CONSTRAINT chk_office_hours_user_language CHECK (preferred_language IN ('TR', 'EN'));

ALTER TABLE office_hours_meeting ADD COLUMN reminder_sent_at TIMESTAMPTZ;
CREATE INDEX idx_office_hours_meeting_reminder_due ON office_hours_meeting (scheduled_at) WHERE reminder_sent_at IS NULL;
