-- The program a mentee asked a mentor in, and the program a mentorship belongs to, so the mentor can tell them apart.
ALTER TABLE mentorship_request ADD COLUMN program_id UUID REFERENCES program (id) ON DELETE SET NULL;
ALTER TABLE mentorship ADD COLUMN program_id UUID REFERENCES program (id) ON DELETE SET NULL;
CREATE INDEX idx_mentorship_request_program ON mentorship_request (program_id);
CREATE INDEX idx_mentorship_program ON mentorship (program_id);

-- Existing rows take their startup's chosen program, else the last program the startup joined.
UPDATE mentorship_request request
SET program_id = COALESCE(
    (SELECT organization.active_program_id FROM organization WHERE organization.id = request.organization_id),
    (SELECT program_startup.program_id FROM program_startup
     WHERE program_startup.organization_id = request.organization_id
     ORDER BY program_startup.created_at DESC LIMIT 1));

UPDATE mentorship mentorship
SET program_id = COALESCE(
    (SELECT organization.active_program_id FROM organization WHERE organization.id = mentorship.organization_id),
    (SELECT program_startup.program_id FROM program_startup
     WHERE program_startup.organization_id = mentorship.organization_id
     ORDER BY program_startup.created_at DESC LIMIT 1));
