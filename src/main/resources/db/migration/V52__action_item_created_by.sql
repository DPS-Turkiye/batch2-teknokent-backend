ALTER TABLE action_item ADD COLUMN created_by_id UUID REFERENCES app_user (id) ON DELETE SET NULL;

CREATE INDEX action_item_created_by_idx ON action_item (created_by_id);
