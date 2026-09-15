ALTER TABLE stored_document ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE stored_document ADD COLUMN reviewed_by_id UUID REFERENCES app_user (id) ON DELETE SET NULL;

CREATE INDEX stored_document_reviewed_by_idx ON stored_document (reviewed_by_id);
