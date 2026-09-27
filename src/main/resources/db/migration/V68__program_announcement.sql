-- Item 9: "Duyurular". A coordinator's program-wide announcement: a notification to every team member and
-- accepted mentor, and a read-only thread in their Mesajlar.
CREATE TABLE program_announcement (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id),
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_program_announcement_program ON program_announcement (program_id, created_at);
