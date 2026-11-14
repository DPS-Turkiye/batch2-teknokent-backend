-- Süreç follows the program's phases (program_phase) instead of the fixed StartupStage list. An assignment or a
-- round may name the phase it belongs to; one that names none is placed by its date when Süreç is read, and lands
-- under "Fazsız görevler" when no phase covers that date. `process_assignment` now marks a Süreç assignment
-- (it used to be "stage is set"). The StartupStage columns stay for one more release and are dropped after that.
-- Fail fast instead of queueing the app behind a long-held lock during the deploy; the migration can be retried.
SET LOCAL lock_timeout = '5s';

ALTER TABLE action_item ADD COLUMN process_assignment BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE action_item SET process_assignment = TRUE WHERE stage IS NOT NULL;

ALTER TABLE action_item ADD COLUMN program_phase_id UUID REFERENCES program_phase (id) ON DELETE SET NULL;
ALTER TABLE startup_round ADD COLUMN program_phase_id UUID REFERENCES program_phase (id) ON DELETE SET NULL;
ALTER TABLE startup_round ALTER COLUMN stage DROP NOT NULL;

-- For the ON DELETE SET NULL lookups when a phase is removed; most rows have no phase.
CREATE INDEX idx_action_item_program_phase ON action_item (program_phase_id) WHERE program_phase_id IS NOT NULL;
CREATE INDEX idx_startup_round_program_phase ON startup_round (program_phase_id) WHERE program_phase_id IS NOT NULL;

-- Backfill: each existing assignment and round is linked to the phase of its startup's program that covers its
-- date (an assignment's due date, else the day it was made; a round's start). The startup's program is resolved as
-- MenteeProgramService.programOf does: a student's personal team takes the founder's program; any other team its
-- active program, else the last one it joined. No match leaves the link empty.
WITH organization_program AS (
    SELECT o.id AS organization_id,
           CASE WHEN o.personal
                THEN (SELECT u.program_id FROM app_user u WHERE u.id = o.founder_id)
                ELSE COALESCE(o.active_program_id,
                              (SELECT ps.program_id FROM program_startup ps
                               WHERE ps.organization_id = o.id
                               ORDER BY ps.created_at DESC, ps.id DESC
                               LIMIT 1))
           END AS program_id
    FROM organization o
)
UPDATE action_item ai
SET program_phase_id = (
    SELECT pp.id
    FROM program_phase pp
    WHERE pp.program_id = op.program_id
      AND pp.start_date IS NOT NULL
      AND pp.start_date <= COALESCE(ai.due_date, (ai.created_at AT TIME ZONE 'Europe/Istanbul')::date)
      AND (pp.end_date IS NULL
           OR pp.end_date >= COALESCE(ai.due_date, (ai.created_at AT TIME ZONE 'Europe/Istanbul')::date))
    ORDER BY pp.position
    LIMIT 1)
FROM organization_program op
WHERE op.organization_id = ai.organization_id
  AND ai.process_assignment
  AND op.program_id IS NOT NULL;

WITH organization_program AS (
    SELECT o.id AS organization_id,
           CASE WHEN o.personal
                THEN (SELECT u.program_id FROM app_user u WHERE u.id = o.founder_id)
                ELSE COALESCE(o.active_program_id,
                              (SELECT ps.program_id FROM program_startup ps
                               WHERE ps.organization_id = o.id
                               ORDER BY ps.created_at DESC, ps.id DESC
                               LIMIT 1))
           END AS program_id
    FROM organization o
)
UPDATE startup_round sr
SET program_phase_id = (
    SELECT pp.id
    FROM program_phase pp
    WHERE pp.program_id = op.program_id
      AND pp.start_date IS NOT NULL
      AND pp.start_date <= sr.starts_on
      AND (pp.end_date IS NULL OR pp.end_date >= sr.starts_on)
    ORDER BY pp.position
    LIMIT 1)
FROM organization_program op
WHERE op.organization_id = sr.organization_id
  AND op.program_id IS NOT NULL;
