CREATE TABLE meeting_assistant_share (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL UNIQUE REFERENCES meeting(id) ON DELETE CASCADE,
    published_by_id UUID REFERENCES app_user(id),
    transcript JSONB,
    transcript_published_at TIMESTAMPTZ,
    report JSONB,
    report_published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
