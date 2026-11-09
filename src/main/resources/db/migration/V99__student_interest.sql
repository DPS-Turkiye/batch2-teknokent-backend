-- An ODTÜ student's interests and goal from the profile completion (and Profilim › İlgi alanları): the board's
-- categories, the sub-topics picked under them, a free-text goal, and whether the 3-step completion was finished
-- or skipped. One row per student; the lists are ordered as the student picked them.
CREATE TABLE student_interest (
    user_id     UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    goal        VARCHAR(1000) NOT NULL DEFAULT '',
    completed   BOOLEAN NOT NULL DEFAULT FALSE,
    skipped     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE student_interest_topic (
    user_id        UUID NOT NULL REFERENCES student_interest (user_id) ON DELETE CASCADE,
    display_order  INTEGER NOT NULL,
    category       VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, display_order),
    CONSTRAINT chk_student_interest_topic CHECK (category IN (
        'ACADEMIC_DEVELOPMENT', 'CAREER_INTERNSHIP', 'TECHNICAL_DIGITAL', 'RESEARCH_ACADEMIC_CAREER',
        'ENTREPRENEURSHIP', 'CAMPUS_LIFE', 'PERSONAL_PROFESSIONAL', 'INTERNATIONAL'))
);

CREATE TABLE student_interest_subtopic (
    user_id        UUID NOT NULL REFERENCES student_interest (user_id) ON DELETE CASCADE,
    display_order  INTEGER NOT NULL,
    subtopic       VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, display_order)
);
