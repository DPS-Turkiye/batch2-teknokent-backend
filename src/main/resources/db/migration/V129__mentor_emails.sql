-- Every account's "E-posta bildirimleri" switch, on until its owner turns it off.
ALTER TABLE notification_preference ADD COLUMN emails BOOLEAN NOT NULL DEFAULT TRUE;

-- When an unanswered request was followed up, so each person is told once; and the last unread-message mail.
ALTER TABLE mentorship_request ADD COLUMN answer_reminded_at TIMESTAMPTZ;
ALTER TABLE mentorship_request ADD COLUMN coordinator_alerted_at TIMESTAMPTZ;
ALTER TABLE mentorship_request ADD COLUMN mentor_mailed_at TIMESTAMPTZ;
