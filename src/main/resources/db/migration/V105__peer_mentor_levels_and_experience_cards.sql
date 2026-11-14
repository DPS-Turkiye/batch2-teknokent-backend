-- İŞKUR peer mentors (the profile wizard): an optional level for each category they offer, and experience cards
-- (internship, club, job) with start and end months on the existing experience list. Teknokent mentors' rows leave
-- the new experience columns empty.
CREATE TABLE peer_mentor_category_level (
    mentor_id UUID NOT NULL REFERENCES mentor_profile (user_id) ON DELETE CASCADE,
    category  VARCHAR(40) NOT NULL,
    level     VARCHAR(16) NOT NULL,
    PRIMARY KEY (mentor_id, category),
    CONSTRAINT chk_peer_mentor_category_level_category CHECK (category IN (
        'ACADEMIC_DEVELOPMENT', 'CAREER_INTERNSHIP', 'TECHNICAL_DIGITAL', 'RESEARCH_ACADEMIC_CAREER',
        'ENTREPRENEURSHIP', 'CAMPUS_LIFE', 'PERSONAL_PROFESSIONAL', 'INTERNATIONAL')),
    CONSTRAINT chk_peer_mentor_category_level_level CHECK (level IN ('BASIC', 'INTERMEDIATE', 'ADVANCED'))
);

ALTER TABLE mentor_experience ADD COLUMN kind VARCHAR(16);
ALTER TABLE mentor_experience ADD COLUMN started_on DATE;
ALTER TABLE mentor_experience ADD COLUMN ended_on DATE;
ALTER TABLE mentor_experience ADD CONSTRAINT chk_mentor_experience_kind
    CHECK (kind IS NULL OR kind IN ('INTERNSHIP', 'CLUB', 'JOB', 'OTHER'));
ALTER TABLE mentor_experience ADD CONSTRAINT chk_mentor_experience_months
    CHECK (ended_on IS NULL OR started_on IS NULL OR ended_on >= started_on);
