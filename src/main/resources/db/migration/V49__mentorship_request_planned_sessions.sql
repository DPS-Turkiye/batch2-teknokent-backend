ALTER TABLE mentorship_request ADD COLUMN planned_session_count INTEGER;
ALTER TABLE mentorship_request ADD CONSTRAINT chk_mentorship_request_planned_session_count
    CHECK (planned_session_count IS NULL OR planned_session_count BETWEEN 1 AND 52);
