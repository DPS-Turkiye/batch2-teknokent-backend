-- Mentor labels: a program's coordinators keep a short list of labels (name + palette color) and give each mentor of
-- the program up to five of them. A label belongs to one program, so the same mentor can carry different labels in
-- different programs, and nobody outside the program sees them.

-- Off by default: a program shows no labels anywhere until its coordinators turn this on. Turning it off keeps the
-- labels; turning it back on shows them again.
ALTER TABLE program
    ADD COLUMN mentor_labels_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- The color is a palette key, not a hex value, so the frontend owns the shades and their contrast.
CREATE TABLE program_mentor_label (
    id         UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    name       VARCHAR(30) NOT NULL,
    color      VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES app_user (id) ON DELETE SET NULL,
    -- Lets an assignment name the label and its program together, so it cannot point at another program's label.
    CONSTRAINT uq_program_mentor_label_program UNIQUE (id, program_id),
    CONSTRAINT chk_program_mentor_label_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 30),
    CONSTRAINT chk_program_mentor_label_color CHECK (color IN ('BLUE', 'GREEN', 'PURPLE', 'ORANGE', 'RED', 'YELLOW',
                                                               'PINK', 'TEAL', 'INDIGO', 'GRAY'))
);

-- One spelling per program: "Fintech" and "fintech" are the same label.
CREATE UNIQUE INDEX uq_program_mentor_label_name ON program_mentor_label (program_id, lower(name));

-- A label on a mentor. Tied to the mentor's membership of the program, so the labels go when the mentor leaves the
-- program's pool (or the account is deleted) and a label is deleted with all its assignments.
CREATE TABLE program_mentor_label_assignment (
    id         UUID PRIMARY KEY,
    label_id   UUID NOT NULL,
    program_id UUID NOT NULL,
    mentor_id  UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_program_mentor_label_assignment UNIQUE (label_id, mentor_id),
    CONSTRAINT fk_program_mentor_label_assignment_label FOREIGN KEY (label_id, program_id)
        REFERENCES program_mentor_label (id, program_id) ON DELETE CASCADE,
    CONSTRAINT fk_program_mentor_label_assignment_member FOREIGN KEY (program_id, mentor_id)
        REFERENCES program_mentor (program_id, mentor_id) ON DELETE CASCADE
);

CREATE INDEX idx_program_mentor_label_assignment_member ON program_mentor_label_assignment (program_id, mentor_id);
CREATE INDEX idx_program_mentor_label_assignment_mentor ON program_mentor_label_assignment (mentor_id);
