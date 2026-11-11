-- The program a feedback was sent from, so Geri bildirim can show and filter by program type (İŞKUR board,
-- Admin IA). Taken when the feedback is sent; older feedback and authors outside any program keep NULL.
ALTER TABLE platform_feedback ADD COLUMN program_id UUID REFERENCES program (id) ON DELETE SET NULL;
ALTER TABLE platform_feedback ADD COLUMN program_type VARCHAR(32);
ALTER TABLE platform_feedback ADD CONSTRAINT chk_platform_feedback_program_type
    CHECK (program_type IS NULL OR program_type IN ('STARTUP_MENTORING', 'PEER_MENTORING', 'OFFICE_HOURS'));
CREATE INDEX ix_platform_feedback_program_type ON platform_feedback (program_type, created_at DESC);
