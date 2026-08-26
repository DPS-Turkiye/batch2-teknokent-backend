CREATE TABLE meeting_note (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL UNIQUE REFERENCES meeting(id) ON DELETE CASCADE,
    content TEXT NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    updated_by_id UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE meeting_todo (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    title VARCHAR(1000) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL,
    created_by_id UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_meeting_todo_display_order CHECK (display_order >= 0)
);

CREATE INDEX idx_meeting_todo_meeting_order
    ON meeting_todo(meeting_id, display_order);
