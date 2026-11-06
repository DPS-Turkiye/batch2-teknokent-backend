-- A mentee can sign up without a startup: the program they chose is kept on the person until they join or create one.
ALTER TABLE app_user ADD COLUMN program_id UUID REFERENCES program (id);
CREATE INDEX idx_app_user_program_status ON app_user (program_id, status);

-- A startup created after signup waits for its program's coordinator. Existing startups were approved with their founder.
ALTER TABLE organization ADD COLUMN approval_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
