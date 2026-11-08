-- Program types (İŞKUR / ODTÜ revision): every existing program is a startup mentoring program.
ALTER TABLE program ADD COLUMN program_type VARCHAR(32) NOT NULL DEFAULT 'STARTUP_MENTORING';
ALTER TABLE program
    ADD CONSTRAINT chk_program_type CHECK (program_type IN ('STARTUP_MENTORING', 'PEER_MENTORING', 'OFFICE_HOURS'));

-- An admin creates a program as a draft (Taslak) and launches it later.
ALTER TABLE program DROP CONSTRAINT chk_program_status;
ALTER TABLE program
    ADD CONSTRAINT chk_program_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED'));
