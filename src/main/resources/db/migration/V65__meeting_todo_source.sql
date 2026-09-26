-- Tasks the meeting report's action items added to a notebook's Yapılacaklar list are marked AI;
-- everything typed in by a person stays MANUAL. Both kinds can be edited, ticked and deleted.
ALTER TABLE meeting_todo
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT chk_meeting_todo_source CHECK (source IN ('MANUAL', 'AI'));
