-- A task set through a mentorship request belongs to that request, so each request lists only its own tasks.
-- Süreç assignments (stage set) belong to the startup, not to one request, and stay null.
ALTER TABLE action_item
    ADD COLUMN mentorship_request_id UUID REFERENCES mentorship_request (id) ON DELETE SET NULL;

CREATE INDEX action_item_mentorship_request_idx ON action_item (mentorship_request_id);

-- Older rows: the meeting's request when there is one.
UPDATE action_item ai
SET mentorship_request_id = m.mentorship_request_id
FROM meeting m
WHERE ai.meeting_id = m.id
  AND m.mentorship_request_id IS NOT NULL
  AND ai.stage IS NULL;

-- Then the meeting mentor's latest accepted request with that startup made before the task.
UPDATE action_item ai
SET mentorship_request_id = (
    SELECT r.id
    FROM mentorship_request r
    JOIN meeting m ON m.id = ai.meeting_id
    WHERE r.organization_id = ai.organization_id
      AND r.mentor_id = m.mentor_id
      AND r.status = 'ACCEPTED'
      AND r.created_at <= ai.created_at
    ORDER BY r.created_at DESC
    LIMIT 1)
WHERE ai.mentorship_request_id IS NULL
  AND ai.meeting_id IS NOT NULL
  AND ai.stage IS NULL;

-- No meeting: the startup's latest accepted request made before the task, the creator's own first.
-- With several such requests this can pick the wrong one, but the task shows once instead of vanishing.
UPDATE action_item ai
SET mentorship_request_id = (
    SELECT r.id
    FROM mentorship_request r
    WHERE r.organization_id = ai.organization_id
      AND r.status = 'ACCEPTED'
      AND r.created_at <= ai.created_at
    ORDER BY (r.mentor_id = ai.created_by_id) DESC NULLS LAST, r.created_at DESC
    LIMIT 1)
WHERE ai.mentorship_request_id IS NULL
  AND ai.stage IS NULL;
