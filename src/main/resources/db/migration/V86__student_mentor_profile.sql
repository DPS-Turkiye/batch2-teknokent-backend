-- Student mentors (İŞKUR): what a mentee sees about a student who mentors. All optional, so existing mentor
-- profiles are unchanged. Internships stay in mentor_experience; skills stay in specialties and offerings.
ALTER TABLE mentor_profile ADD COLUMN department VARCHAR(255);
ALTER TABLE mentor_profile ADD COLUMN study_year VARCHAR(32);
ALTER TABLE mentor_profile ADD COLUMN erasmus VARCHAR(500);

CREATE TABLE mentor_strong_course (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    course VARCHAR(255) NOT NULL,
    PRIMARY KEY (mentor_id, display_order)
);

CREATE TABLE mentor_project (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    project VARCHAR(500) NOT NULL,
    PRIMARY KEY (mentor_id, display_order)
);
