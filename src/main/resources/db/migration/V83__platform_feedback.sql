CREATE TABLE platform_feedback (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('FEEDBACK', 'SUGGESTION')),
    subject VARCHAR(120) NOT NULL CHECK (length(trim(subject)) > 0),
    message VARCHAR(2000) NOT NULL CHECK (length(trim(message)) > 0),
    language VARCHAR(8) NOT NULL CHECK (language IN ('TR', 'EN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ
);

CREATE INDEX idx_platform_feedback_created ON platform_feedback (created_at DESC, id DESC);
CREATE INDEX idx_platform_feedback_unread ON platform_feedback (created_at DESC, id DESC) WHERE read_at IS NULL;
CREATE INDEX idx_platform_feedback_author ON platform_feedback (author_id);
