-- Item 9: a coordinator proposes a meeting time for a mentorship; the mentor and the team lead each accept it.
-- When both have, a normal meeting is created (or the replaced meeting moves). One decline closes the proposal.
CREATE TABLE meeting_proposal (
    id UUID PRIMARY KEY,
    mentorship_id UUID NOT NULL REFERENCES mentorship(id) ON DELETE CASCADE,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    proposed_by UUID NOT NULL REFERENCES app_user(id),
    scheduled_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL,
    agenda VARCHAR(500),
    replaces_meeting_id UUID REFERENCES meeting(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,
    mentor_accepted_at TIMESTAMPTZ,
    team_accepted_at TIMESTAMPTZ,
    team_responder_id UUID REFERENCES app_user(id),
    declined_by UUID REFERENCES app_user(id),
    decline_reason VARCHAR(500),
    meeting_id UUID REFERENCES meeting(id) ON DELETE SET NULL,
    reminded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_meeting_proposal_mentorship ON meeting_proposal (mentorship_id, status);
CREATE INDEX idx_meeting_proposal_program ON meeting_proposal (program_id, status);
