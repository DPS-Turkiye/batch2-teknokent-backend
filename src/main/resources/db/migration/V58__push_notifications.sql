ALTER TABLE notification
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'GENERAL';

CREATE TABLE push_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token VARCHAR(512) NOT NULL UNIQUE,
    user_agent VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_push_token_user ON push_token (user_id);

CREATE TABLE notification_preference (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    messages BOOLEAN NOT NULL DEFAULT TRUE,
    meeting_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    meeting_changes BOOLEAN NOT NULL DEFAULT TRUE,
    new_tasks BOOLEAN NOT NULL DEFAULT TRUE,
    task_deadlines BOOLEAN NOT NULL DEFAULT FALSE,
    startup_activity BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE meeting ADD COLUMN reminder_sent_at TIMESTAMPTZ;
ALTER TABLE action_item ADD COLUMN deadline_reminded_at TIMESTAMPTZ;

CREATE INDEX idx_meeting_reminder_due
    ON meeting (scheduled_at)
    WHERE status = 'SCHEDULED' AND reminder_sent_at IS NULL;
