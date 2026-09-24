CREATE TABLE coordinator_conversation (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organization(id) ON DELETE CASCADE,
    mentor_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_at TIMESTAMPTZ,
    last_message_preview VARCHAR(200),
    coordinator_read_at TIMESTAMPTZ,
    counterpart_read_at TIMESTAMPTZ,
    CONSTRAINT chk_coordinator_conversation_one_counterpart
        CHECK ((organization_id IS NULL) <> (mentor_id IS NULL)),
    CONSTRAINT uq_coordinator_conversation_team UNIQUE (program_id, organization_id),
    CONSTRAINT uq_coordinator_conversation_mentor UNIQUE (program_id, mentor_id)
);

CREATE INDEX idx_coordinator_conversation_organization ON coordinator_conversation (organization_id);
CREATE INDEX idx_coordinator_conversation_mentor ON coordinator_conversation (mentor_id);

CREATE TABLE coordinator_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES coordinator_conversation(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coordinator_message_conversation ON coordinator_message (conversation_id, created_at);
CREATE INDEX idx_coordinator_message_sender ON coordinator_message (sender_id);
