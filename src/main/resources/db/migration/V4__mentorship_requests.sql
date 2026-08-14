CREATE TABLE mentorship_request (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES app_user(id),
    organization_id UUID NOT NULL REFERENCES organization(id),
    mentor_id UUID NOT NULL REFERENCES app_user(id),
    requested_for_name TEXT NOT NULL,
    support_area TEXT NOT NULL,
    summary TEXT NOT NULL,
    urgency TEXT NOT NULL,
    mentorship_format TEXT NOT NULL,
    meeting_mode TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    requester_message TEXT,
    mentor_message TEXT,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentorship_request_requested_for_name
        CHECK (BTRIM(requested_for_name) <> ''),
    CONSTRAINT chk_mentorship_request_support_area
        CHECK (BTRIM(support_area) <> ''),
    CONSTRAINT chk_mentorship_request_summary
        CHECK (BTRIM(summary) <> ''),
    CONSTRAINT chk_mentorship_request_urgency
        CHECK (urgency IN ('THIS_WEEK', 'NEXT_TWO_WEEKS', 'THIS_MONTH', 'NOT_URGENT')),
    CONSTRAINT chk_mentorship_request_format
        CHECK (mentorship_format IN ('SINGLE_MEETING', 'SHORT_TERM', 'REGULAR', 'EXPERT_REFERRAL')),
    CONSTRAINT chk_mentorship_request_meeting_mode
        CHECK (meeting_mode IN ('ONLINE', 'IN_PERSON', 'NO_PREFERENCE')),
    CONSTRAINT chk_mentorship_request_status
        CHECK (status IN ('PENDING', 'MORE_INFO_REQUESTED', 'ACCEPTED', 'DECLINED', 'WITHDRAWN'))
);

CREATE INDEX idx_mentorship_request_requester_created
    ON mentorship_request(requester_id, created_at DESC);

CREATE INDEX idx_mentorship_request_mentor_status_created
    ON mentorship_request(mentor_id, status, created_at DESC);

CREATE INDEX idx_mentorship_request_organization_created
    ON mentorship_request(organization_id, created_at DESC);
