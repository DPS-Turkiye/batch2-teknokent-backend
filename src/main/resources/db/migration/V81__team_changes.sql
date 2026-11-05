-- Who was in which startup and when. Shown to admins and coordinators as a person's team history.
CREATE TABLE team_membership (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    exit_reason VARCHAR(16)
);
CREATE INDEX idx_team_membership_user ON team_membership (user_id, joined_at);
CREATE INDEX idx_team_membership_organization ON team_membership (organization_id, left_at);

-- Current members start their history at their account's creation: the real join date was never stored.
INSERT INTO team_membership (id, user_id, organization_id, joined_at)
SELECT gen_random_uuid(), id, organization_id, created_at
FROM app_user
WHERE organization_id IS NOT NULL;

-- A member asking to leave, or the leader asking to remove a member; the program's coordinator decides.
CREATE TABLE team_change_request (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    program_id UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    requested_by_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    decided_by_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ
);
CREATE INDEX idx_team_change_request_program_status ON team_change_request (program_id, status);
CREATE INDEX idx_team_change_request_organization_status ON team_change_request (organization_id, status);

-- The last member left: the startup and its history stay, but it is no longer a team and its code stops working.
ALTER TABLE organization ADD COLUMN archived_at TIMESTAMPTZ;

-- A task whose owner left the team keeps who had it, and when they left, next to its new owner.
ALTER TABLE action_item ADD COLUMN previous_owner_id UUID REFERENCES app_user (id) ON DELETE SET NULL;
ALTER TABLE action_item ADD COLUMN owner_left_at TIMESTAMPTZ;
