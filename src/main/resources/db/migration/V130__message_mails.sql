-- The last unread-message mail to each side of a conversation, so a person is mailed once per batch of messages.
ALTER TABLE mentorship_request ADD COLUMN mentee_mailed_at TIMESTAMPTZ;
ALTER TABLE coordinator_conversation ADD COLUMN coordinator_mailed_at TIMESTAMPTZ;
ALTER TABLE coordinator_conversation ADD COLUMN counterpart_mailed_at TIMESTAMPTZ;
