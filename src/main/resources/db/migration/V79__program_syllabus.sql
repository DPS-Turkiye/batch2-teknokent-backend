ALTER TABLE program
    ADD COLUMN syllabus_document_id UUID REFERENCES stored_document (id) ON DELETE SET NULL;

ALTER TABLE program_phase
    ADD COLUMN expected_output VARCHAR(200);
