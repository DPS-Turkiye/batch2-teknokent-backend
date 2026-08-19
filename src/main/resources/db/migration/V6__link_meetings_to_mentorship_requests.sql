ALTER TABLE meeting
    ADD COLUMN mentorship_request_id UUID
        REFERENCES mentorship_request(id);

CREATE UNIQUE INDEX uq_meeting_mentorship_request
    ON meeting(mentorship_request_id)
    WHERE mentorship_request_id IS NOT NULL;
