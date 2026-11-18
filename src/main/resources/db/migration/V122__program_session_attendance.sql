-- Attendance by session: each program week has a lesson (Eğitim / Konuşmacı) and a workshop, and a participant
-- checks in to each by scanning that session's QR code instead of tapping "I attended" once a week.

-- A coordinator can turn attendance off for a program (no QR codes, no check-ins, no attendance card for the teams).
-- On by default, so the programs running today keep taking attendance.
ALTER TABLE program
    ADD COLUMN attendance_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- One row per participant per session. The rows written before this change were weekly self-check-ins: they stay,
-- as session_kind WEEK / method SELF, and the report shows them beside that week's sessions. Nothing writes WEEK
-- rows any more. recorded_by is the coordinator who marked a MANUAL row.
ALTER TABLE program_attendance
    ADD COLUMN session_kind VARCHAR(16) NOT NULL DEFAULT 'WEEK',
    ADD COLUMN method       VARCHAR(16) NOT NULL DEFAULT 'SELF',
    ADD COLUMN recorded_by  UUID REFERENCES app_user (id) ON DELETE SET NULL;

ALTER TABLE program_attendance
    ALTER COLUMN session_kind DROP DEFAULT,
    ALTER COLUMN method DROP DEFAULT,
    DROP CONSTRAINT uq_program_attendance,
    ADD CONSTRAINT uq_program_attendance UNIQUE (program_id, user_id, week_number, session_kind),
    ADD CONSTRAINT chk_program_attendance_kind CHECK (session_kind IN ('WEEK', 'LESSON', 'WORKSHOP')),
    ADD CONSTRAINT chk_program_attendance_method CHECK (method IN ('SELF', 'QR', 'MANUAL')),
    ADD CONSTRAINT chk_program_attendance_recorded_by CHECK (method = 'MANUAL' OR recorded_by IS NULL);

-- The QR code of one session: an unguessable token in the link, one per program, week and kind. Kept apart from
-- program_week_session, whose rows are replaced each time the plan is read again; the session's day and time are
-- always taken from the current plan. Regenerating replaces the token, so the old link stops working.
CREATE TABLE program_session_qr (
    id           UUID PRIMARY KEY,
    program_id   UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    week_number  INTEGER NOT NULL,
    session_kind VARCHAR(16) NOT NULL,
    token        VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_program_session_qr UNIQUE (program_id, week_number, session_kind),
    CONSTRAINT uq_program_session_qr_token UNIQUE (token),
    CONSTRAINT chk_program_session_qr_week CHECK (week_number >= 1),
    CONSTRAINT chk_program_session_qr_kind CHECK (session_kind IN ('LESSON', 'WORKSHOP'))
);
