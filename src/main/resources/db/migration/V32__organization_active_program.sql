-- A startup is assigned to programs by a coordinator (program_startup). Which of
-- those assignments is the startup's *active* program is the startup's own choice,
-- so it belongs on the organization rather than on the assignment row: one active
-- program per organization, and the invalid "two actives" state cannot be written.
--
-- Nullable on purpose. A startup with no assignment, or one that has not chosen
-- yet, has no active program, and the UI shows its "no program" state.
ALTER TABLE organization
    ADD COLUMN active_program_id UUID;

-- Deleting a program must not leave an organization pointing at a row that is
-- gone; the startup simply falls back to "no program".
ALTER TABLE organization
    ADD CONSTRAINT fk_organization_active_program
        FOREIGN KEY (active_program_id) REFERENCES program (id)
        ON DELETE SET NULL;

CREATE INDEX idx_organization_active_program ON organization (active_program_id);
