ALTER TABLE meeting_assistant_share
    ADD COLUMN IF NOT EXISTS report_s3_key VARCHAR(1024);
