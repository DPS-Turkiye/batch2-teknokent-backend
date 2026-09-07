ALTER TABLE meeting_assistant_share
    ADD COLUMN IF NOT EXISTS transcript_s3_key VARCHAR(1024);
