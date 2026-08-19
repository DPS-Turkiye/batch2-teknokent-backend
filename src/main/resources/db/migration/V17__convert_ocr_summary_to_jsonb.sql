ALTER TABLE profile_analysis
    ALTER COLUMN ocr_summary DROP DEFAULT;

ALTER TABLE profile_analysis
    ALTER COLUMN ocr_summary TYPE JSONB
    USING ocr_summary::jsonb;

ALTER TABLE profile_analysis
    ALTER COLUMN ocr_summary SET DEFAULT '{}'::jsonb;
