ALTER TABLE action_item
    ADD COLUMN mentorship_request_id UUID REFERENCES mentorship_request(id) ON DELETE CASCADE;

UPDATE action_item AS action
SET mentorship_request_id = meeting.mentorship_request_id
FROM meeting
WHERE action.meeting_id = meeting.id
  AND meeting.mentorship_request_id IS NOT NULL;

CREATE INDEX idx_action_item_mentorship_request_created
    ON action_item (mentorship_request_id, created_at DESC);
