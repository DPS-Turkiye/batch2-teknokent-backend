-- Item 9: "Koordinatör notu ekle". Notes on a mentorship that only the program's coordinators see.
CREATE TABLE coordinator_note (
    id UUID PRIMARY KEY,
    mentorship_id UUID NOT NULL REFERENCES mentorship(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id),
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coordinator_note_mentorship ON coordinator_note (mentorship_id, created_at);
