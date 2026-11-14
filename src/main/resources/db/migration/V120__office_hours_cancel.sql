-- Either side can cancel an office-hours meeting before it starts. The meeting stays, marked with when, by whom and
-- why, as a mentor meeting keeps its CANCELLED status; its request ends as CANCELLED. A cancelled meeting no longer
-- holds the academic's time, so the one-meeting-per-moment rule covers live meetings only.
ALTER TABLE office_hours_meeting
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID REFERENCES office_hours_user(id) ON DELETE SET NULL,
    ADD COLUMN cancellation_reason TEXT;

DROP INDEX uq_office_hours_meeting_instructor_time;
CREATE UNIQUE INDEX uq_office_hours_meeting_instructor_time ON office_hours_meeting (instructor_id, scheduled_at)
    WHERE cancelled_at IS NULL;

ALTER TABLE office_hours_request DROP CONSTRAINT chk_office_hours_request_status;
ALTER TABLE office_hours_request ADD CONSTRAINT chk_office_hours_request_status CHECK (status IN
    ('PENDING', 'ACCEPTED', 'DECLINED', 'MORE_INFO_REQUESTED', 'COUNTER_PROPOSED', 'WITHDRAWN', 'CANCELLED'));
