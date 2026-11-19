-- Per-week visibility for a program's mentees and accepted mentors, set by its coordinator on the weekly plan.
-- AUTO is the behaviour before this column: a week is seen once it begins. SHOWN opens a week early, HIDDEN keeps
-- it out of the participants' Süreç and Takvim whatever its dates. The plan's rows are replaced on every save, so
-- the service carries the value over by week number. Default AUTO, so no running program changes.
ALTER TABLE program_week
    ADD COLUMN mentee_visibility VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD CONSTRAINT chk_program_week_mentee_visibility CHECK (mentee_visibility IN ('AUTO', 'SHOWN', 'HIDDEN'));
