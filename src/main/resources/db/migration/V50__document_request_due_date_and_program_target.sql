ALTER TABLE document_request_item ADD COLUMN due_date DATE;
ALTER TABLE document_request_item ADD COLUMN program_id UUID REFERENCES program (id) ON DELETE CASCADE;

ALTER TABLE document_request_item DROP CONSTRAINT document_request_item_one_target;
ALTER TABLE document_request_item ADD CONSTRAINT document_request_item_one_target CHECK (
    (mentorship_request_id IS NOT NULL)::INT
    + (meeting_id IS NOT NULL)::INT
    + (mentorship_id IS NOT NULL)::INT
    + (program_id IS NOT NULL)::INT
    = 1
);

CREATE INDEX document_request_item_program_idx
    ON document_request_item (program_id);
