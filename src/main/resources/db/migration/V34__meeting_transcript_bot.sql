ALTER TABLE meeting
    ADD COLUMN transcribe_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE meeting
    ADD COLUMN bot_dispatched_at TIMESTAMPTZ;
