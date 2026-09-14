ALTER TABLE stored_document ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE stored_document ADD COLUMN reviewed_by_id UUID REFERENCES app_user (id) ON DELETE SET NULL;
