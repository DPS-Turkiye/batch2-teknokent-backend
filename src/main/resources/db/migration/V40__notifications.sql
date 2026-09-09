CREATE TABLE notification (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    body VARCHAR(500) NOT NULL,
    href VARCHAR(500),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_recipient_created
    ON notification (recipient_id, created_at DESC);
