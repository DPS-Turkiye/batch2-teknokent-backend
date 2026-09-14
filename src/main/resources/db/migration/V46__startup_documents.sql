ALTER TABLE stored_document ADD COLUMN meeting_id UUID REFERENCES meeting(id) ON DELETE SET NULL;

CREATE INDEX idx_stored_document_organization ON stored_document (organization_id, created_at DESC);
CREATE INDEX idx_stored_document_meeting ON stored_document (meeting_id);

CREATE TABLE stored_document_share (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES stored_document(id) ON DELETE CASCADE,
    mentor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    shared_by_id UUID NOT NULL REFERENCES app_user(id),
    shared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stored_document_share UNIQUE (document_id, mentor_id)
);

CREATE INDEX idx_stored_document_share_mentor ON stored_document_share (mentor_id);
