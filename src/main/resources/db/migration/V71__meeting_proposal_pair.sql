-- A proposal now names its pair directly, so a mentor or a team can propose a new time for a meeting that has no
-- program mentorship behind it. Coordinator proposals keep their mentorship and program.
ALTER TABLE meeting_proposal ADD COLUMN organization_id UUID REFERENCES organization(id) ON DELETE CASCADE;
ALTER TABLE meeting_proposal ADD COLUMN mentor_id UUID REFERENCES app_user(id);

UPDATE meeting_proposal
SET organization_id = (SELECT m.organization_id FROM mentorship m WHERE m.id = meeting_proposal.mentorship_id),
    mentor_id = (SELECT m.mentor_id FROM mentorship m WHERE m.id = meeting_proposal.mentorship_id);

ALTER TABLE meeting_proposal ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE meeting_proposal ALTER COLUMN mentor_id SET NOT NULL;
ALTER TABLE meeting_proposal ALTER COLUMN mentorship_id DROP NOT NULL;
ALTER TABLE meeting_proposal ALTER COLUMN program_id DROP NOT NULL;

CREATE INDEX idx_meeting_proposal_mentor ON meeting_proposal (mentor_id, status);
CREATE INDEX idx_meeting_proposal_organization ON meeting_proposal (organization_id, status);
CREATE INDEX idx_meeting_proposal_replaces ON meeting_proposal (replaces_meeting_id, status);
