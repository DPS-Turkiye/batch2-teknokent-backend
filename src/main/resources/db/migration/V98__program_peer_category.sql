-- A peer-mentoring program's active categories. None is active until a coordinator turns it on in Program
-- Ayarları; students and peer mentors only see the active ones (board: "Akran mentörlüğü kategorileri").
CREATE TABLE program_peer_category (
    id            UUID PRIMARY KEY,
    program_id    UUID NOT NULL REFERENCES program (id) ON DELETE CASCADE,
    category      VARCHAR(40) NOT NULL,
    activated_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    activated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_program_peer_category UNIQUE (program_id, category),
    CONSTRAINT chk_program_peer_category CHECK (category IN (
        'ACADEMIC_DEVELOPMENT', 'CAREER_INTERNSHIP', 'TECHNICAL_DIGITAL', 'RESEARCH_ACADEMIC_CAREER',
        'ENTREPRENEURSHIP', 'CAMPUS_LIFE', 'PERSONAL_PROFESSIONAL', 'INTERNATIONAL'))
);
