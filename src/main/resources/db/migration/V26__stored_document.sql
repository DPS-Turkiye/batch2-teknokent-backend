CREATE TABLE stored_document (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES app_user (id),
    organization_id UUID REFERENCES organization (id),
    kind VARCHAR(32) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    s3_key VARCHAR(512) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX stored_document_user_id_idx ON stored_document (user_id);
CREATE INDEX stored_document_organization_id_idx ON stored_document (organization_id);
