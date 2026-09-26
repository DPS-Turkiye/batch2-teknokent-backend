CREATE TABLE mentorship_intervention (
    id UUID PRIMARY KEY,
    mentorship_id UUID NOT NULL REFERENCES mentorship(id) ON DELETE CASCADE,
    coordinator_id UUID NOT NULL REFERENCES app_user(id),
    opened_by UUID NOT NULL REFERENCES app_user(id),
    notification_id UUID REFERENCES notification(id),
    action VARCHAR(160) NOT NULL,
    note VARCHAR(500),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_open_mentorship_intervention
    ON mentorship_intervention (mentorship_id)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_mentorship_intervention_opened
    ON mentorship_intervention (opened_at DESC);
