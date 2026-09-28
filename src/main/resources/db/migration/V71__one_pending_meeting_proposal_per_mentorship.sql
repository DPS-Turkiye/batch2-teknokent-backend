-- Preserve the oldest open proposal for each mentorship and close legacy duplicates before
-- installing the invariant. This keeps one existing proposal actionable for each pair.
WITH ranked_pending AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mentorship_id
               ORDER BY created_at ASC, id ASC
           ) AS position
    FROM meeting_proposal
    WHERE status = 'PENDING'
)
UPDATE meeting_proposal proposal
SET status = 'WITHDRAWN',
    updated_at = NOW()
FROM ranked_pending ranked
WHERE proposal.id = ranked.id
  AND ranked.position > 1;

CREATE UNIQUE INDEX uq_meeting_proposal_one_pending_per_mentorship
    ON meeting_proposal (mentorship_id)
    WHERE status = 'PENDING';
