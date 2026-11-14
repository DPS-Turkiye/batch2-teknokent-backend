-- Office hours for people signed in to Menton through ODTÜ, not only through a Moodle launch.
--
-- A person is either a Moodle person (issuer + subject, as before) or a Menton account (app_user_id); a later,
-- confirmed Moodle link may give one row both. Requests and meetings made from Menton belong to no course.
-- An academician appears in the students' list only after opting in (listed), with where and how to meet.

ALTER TABLE office_hours_user ADD COLUMN app_user_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE office_hours_user ADD CONSTRAINT uq_office_hours_user_app_user UNIQUE (app_user_id);
ALTER TABLE office_hours_user ALTER COLUMN issuer DROP NOT NULL;
ALTER TABLE office_hours_user ALTER COLUMN subject DROP NOT NULL;
ALTER TABLE office_hours_user ADD CONSTRAINT chk_office_hours_user_identity CHECK (
    (issuer IS NOT NULL AND subject IS NOT NULL)
    OR (issuer IS NULL AND subject IS NULL AND app_user_id IS NOT NULL));

ALTER TABLE office_hours_request ALTER COLUMN course_id DROP NOT NULL;
ALTER TABLE office_hours_meeting ALTER COLUMN course_id DROP NOT NULL;

-- A Menton student's requests across academicians (the course-first index doesn't serve a course-free list).
CREATE INDEX idx_office_hours_request_student ON office_hours_request (student_id, created_at);

ALTER TABLE office_hours_settings ADD COLUMN listed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE office_hours_settings ADD COLUMN default_place VARCHAR(200);
ALTER TABLE office_hours_settings ADD COLUMN online_link VARCHAR(500);
ALTER TABLE office_hours_settings ADD COLUMN contact_email VARCHAR(320);
