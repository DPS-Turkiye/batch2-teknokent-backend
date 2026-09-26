-- One read mark per side, like coordinator_conversation: the startup's team shares one.
ALTER TABLE mentorship_request ADD COLUMN mentor_read_at TIMESTAMPTZ;
ALTER TABLE mentorship_request ADD COLUMN mentee_read_at TIMESTAMPTZ;
