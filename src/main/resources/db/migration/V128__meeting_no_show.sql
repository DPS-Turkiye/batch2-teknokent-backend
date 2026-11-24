-- The mentor's "Girişimci katılmadı" on a meeting: when it was marked, and the optional note for the coordinators.
ALTER TABLE meeting ADD COLUMN no_show_at TIMESTAMPTZ;
ALTER TABLE meeting ADD COLUMN no_show_note VARCHAR(255);
