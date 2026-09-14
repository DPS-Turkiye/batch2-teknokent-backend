ALTER TABLE action_item ADD COLUMN created_by_id UUID REFERENCES app_user (id) ON DELETE SET NULL;
