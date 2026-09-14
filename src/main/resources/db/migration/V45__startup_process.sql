ALTER TABLE organization ADD COLUMN current_stage VARCHAR(32);
ALTER TABLE organization ADD CONSTRAINT chk_organization_current_stage
    CHECK (current_stage IS NULL OR current_stage IN (
        'PROBLEM', 'SOLUTION', 'MVP', 'BUSINESS_MODEL', 'MARKET', 'FINANCE', 'PITCH'
    ));

CREATE TABLE startup_round (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    created_by_id UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_startup_round_stage CHECK (stage IN (
        'PROBLEM', 'SOLUTION', 'MVP', 'BUSINESS_MODEL', 'MARKET', 'FINANCE', 'PITCH'
    )),
    CONSTRAINT chk_startup_round_dates CHECK (ends_on >= starts_on)
);

CREATE INDEX idx_startup_round_organization ON startup_round (organization_id, starts_on DESC);

ALTER TABLE action_item ADD COLUMN stage VARCHAR(32);
ALTER TABLE action_item ADD CONSTRAINT chk_action_item_stage
    CHECK (stage IS NULL OR stage IN (
        'PROBLEM', 'SOLUTION', 'MVP', 'BUSINESS_MODEL', 'MARKET', 'FINANCE', 'PITCH'
    ));
ALTER TABLE action_item ADD COLUMN round_id UUID REFERENCES startup_round(id) ON DELETE SET NULL;
ALTER TABLE action_item ADD COLUMN submission_document_id UUID REFERENCES stored_document(id) ON DELETE SET NULL;

CREATE INDEX idx_action_item_round ON action_item (round_id);
