CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'MENTOR', 'MENTEE'))
);

CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mentorship (
    id UUID PRIMARY KEY,
    mentor_id UUID NOT NULL REFERENCES app_user(id),
    organization_id UUID NOT NULL REFERENCES organization(id),
    status VARCHAR(32) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    goals TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentorship_status CHECK (status IN ('PLANNED', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_mentorship_dates CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE TABLE progress_update (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    author_id UUID NOT NULL REFERENCES app_user(id),
    category VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    evidence TEXT,
    recorded_at DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_progress_status CHECK (status IN ('ON_TRACK', 'AT_RISK', 'BLOCKED', 'COMPLETED'))
);

CREATE TABLE bottleneck (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    owner_id UUID REFERENCES app_user(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_resolution_date DATE,
    resolution_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_bottleneck_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_bottleneck_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED'))
);

CREATE TABLE meeting (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    mentor_id UUID REFERENCES app_user(id),
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL,
    agenda TEXT,
    notes TEXT,
    outcomes TEXT,
    meet_link VARCHAR(2048),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_meeting_status CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_meeting_duration CHECK (duration_minutes > 0)
);

CREATE TABLE action_item (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id),
    meeting_id UUID REFERENCES meeting(id),
    owner_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    due_date DATE,
    completion_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_action_item_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_mentorship_organization ON mentorship(organization_id);
CREATE INDEX idx_progress_organization_recorded ON progress_update(organization_id, recorded_at DESC);
CREATE INDEX idx_bottleneck_organization_status ON bottleneck(organization_id, status);
CREATE INDEX idx_meeting_scheduled_at ON meeting(scheduled_at);
CREATE INDEX idx_action_item_status_due ON action_item(status, due_date);

INSERT INTO app_user (id, email, full_name, role) VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@teknokent.demo', 'Demo Administrator', 'ADMIN'),
    ('00000000-0000-0000-0000-000000000002', 'mentor@teknokent.demo', 'Demo Mentor', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000003', 'founder@startup.demo', 'Demo Founder', 'MENTEE');

INSERT INTO organization (id, name, description) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Demo Startup', 'Seed organization for local prototype development.');

INSERT INTO mentorship (
    id, mentor_id, organization_id, status, start_date, goals
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    'ACTIVE',
    CURRENT_DATE,
    'Validate the product and prepare the first pilot.'
);
