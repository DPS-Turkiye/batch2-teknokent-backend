-- A coordinator's announcement goes to the whole program (EVERYONE, what every earlier announcement did), only its
-- accepted mentors (MENTORS), or only its teams' members (PARTICIPANTS). The audience decides both who is notified
-- and whose Mesajlar shows the announcement.
ALTER TABLE program_announcement ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT 'EVERYONE';
ALTER TABLE program_announcement ADD CONSTRAINT chk_program_announcement_audience
    CHECK (audience IN ('EVERYONE', 'MENTORS', 'PARTICIPANTS'));
