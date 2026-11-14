-- The evidence a peer mentor attaches in the wizard's "Belgeler ve kanıtlar" step: an uploaded file (an ordinary
-- stored_document of kind peer-evidence, uploaded through DocumentService) or, for a portfolio, a link. Visibility says
-- who besides the mentor sees it: their program's coordinators always (VERIFICATION_ONLY), and with PROFILE also the
-- students of that program. Deleting the stored document deletes this row with it.
-- V116 is reserved for #204.
CREATE TABLE peer_mentor_document (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    evidence_type VARCHAR(32)  NOT NULL,
    visibility    VARCHAR(24)  NOT NULL,
    document_id   UUID UNIQUE REFERENCES stored_document (id) ON DELETE CASCADE,
    url           VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_peer_mentor_document_visibility CHECK (visibility IN ('VERIFICATION_ONLY', 'PROFILE')),
    CONSTRAINT chk_peer_mentor_document_evidence_type CHECK (evidence_type IN (
        'TRANSCRIPT', 'CV', 'CERTIFICATE', 'PORTFOLIO', 'REFERENCE_LETTER', 'AWARD', 'VOLUNTEERING', 'PROJECT_REPORT')),
    -- A portfolio is a link and nothing else; every other type is a file and nothing else.
    CONSTRAINT chk_peer_mentor_document_file_or_link CHECK (
        (evidence_type = 'PORTFOLIO' AND url IS NOT NULL AND document_id IS NULL)
        OR (evidence_type <> 'PORTFOLIO' AND document_id IS NOT NULL AND url IS NULL))
);

-- A mentor's own list, oldest first, and their profile's evidence.
CREATE INDEX idx_peer_mentor_document_user ON peer_mentor_document (user_id, created_at);
