CREATE TABLE document_request_item (
    id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by_id UUID NOT NULL REFERENCES app_user (id),
    organization_id UUID NOT NULL REFERENCES organization (id),
    mentorship_request_id UUID REFERENCES mentorship_request (id) ON DELETE CASCADE,
    meeting_id UUID REFERENCES meeting (id) ON DELETE CASCADE,
    mentorship_id UUID REFERENCES mentorship (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT document_request_item_one_target CHECK (
        (mentorship_request_id IS NOT NULL)::INT
        + (meeting_id IS NOT NULL)::INT
        + (mentorship_id IS NOT NULL)::INT
        = 1
    )
);

CREATE INDEX document_request_item_request_idx
    ON document_request_item (mentorship_request_id);
CREATE INDEX document_request_item_meeting_idx
    ON document_request_item (meeting_id);
CREATE INDEX document_request_item_mentorship_idx
    ON document_request_item (mentorship_id);
CREATE INDEX document_request_item_org_idx
    ON document_request_item (organization_id);

CREATE TABLE document_request_upload (
    id UUID PRIMARY KEY,
    item_id UUID NOT NULL REFERENCES document_request_item (id) ON DELETE CASCADE,
    stored_document_id UUID NOT NULL REFERENCES stored_document (id),
    uploaded_by_id UUID NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX document_request_upload_item_idx
    ON document_request_upload (item_id);
