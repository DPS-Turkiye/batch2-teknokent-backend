ALTER TABLE mentorship_request_message DROP CONSTRAINT chk_mentorship_request_message_type;
ALTER TABLE mentorship_request_message ADD CONSTRAINT chk_mentorship_request_message_type
    CHECK (message_type IN ('MENTOR_QUESTION', 'MENTEE_REPLY', 'MENTOR_MESSAGE', 'MENTEE_MESSAGE'));
