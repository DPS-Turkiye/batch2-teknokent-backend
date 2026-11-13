-- A record of each data-processing consent: who gave it, whom it is about, for what, to which text version and when.
-- The first purpose is reading a CV with an external AI service. Rows are written once and never changed.
-- A person still signing up has no account yet, so they are named by their Firebase uid; one of the two must be set.
-- V111 is left to the held Office Hours change (#204), which moves its V109 above V110.
CREATE TABLE data_processing_consent (
    id                     UUID PRIMARY KEY,
    consenter_user_id      UUID REFERENCES app_user (id),
    consenter_firebase_uid VARCHAR(128),
    subject_user_id        UUID REFERENCES app_user (id),
    purpose                VARCHAR(32)  NOT NULL,
    source                 VARCHAR(32)  NOT NULL,
    text_version           VARCHAR(64)  NOT NULL,
    accepted_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_data_processing_consent_consenter
        CHECK (consenter_user_id IS NOT NULL OR consenter_firebase_uid IS NOT NULL),
    CONSTRAINT chk_data_processing_consent_purpose CHECK (purpose IN ('CV_PARSING')),
    CONSTRAINT chk_data_processing_consent_source
        CHECK (source IN ('PROFILE_ANALYSIS', 'PREVIEW_UPLOAD', 'PREVIEW_STORED_CV'))
);

-- Erasure deletes by each of these, and an access request reads by them.
CREATE INDEX idx_data_processing_consent_consenter ON data_processing_consent (consenter_user_id);
CREATE INDEX idx_data_processing_consent_firebase_uid ON data_processing_consent (consenter_firebase_uid);
CREATE INDEX idx_data_processing_consent_subject ON data_processing_consent (subject_user_id);
