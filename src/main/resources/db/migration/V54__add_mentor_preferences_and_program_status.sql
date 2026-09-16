ALTER TABLE mentor_profile
    ADD COLUMN meeting_frequency VARCHAR(32),
    ADD COLUMN prep_file_requirement VARCHAR(32),
    ADD CONSTRAINT chk_mentor_profile_meeting_frequency
        CHECK (meeting_frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'AS_NEEDED')),
    ADD CONSTRAINT chk_mentor_profile_prep_file_requirement
        CHECK (prep_file_requirement IN ('REQUIRED', 'OPTIONAL', 'NOT_NEEDED'));

CREATE TABLE mentor_language (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    language VARCHAR(32) NOT NULL,
    PRIMARY KEY (mentor_id, display_order),
    CONSTRAINT chk_mentor_language
        CHECK (language IN ('TURKISH', 'ENGLISH', 'GERMAN', 'FRENCH', 'SPANISH', 'ARABIC', 'RUSSIAN'))
);

ALTER TABLE program
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT chk_program_status CHECK (status IN ('ACTIVE', 'COMPLETED'));

ALTER TABLE program_mentor
    ADD COLUMN role VARCHAR(80);
