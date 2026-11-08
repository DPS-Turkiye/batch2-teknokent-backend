-- ODTÜ (and later other institutions) sign in through their own login; Menton keeps who they are apart from
-- the profile they edit. A person is keyed by the institution's persistent id, not by app_user, because
-- academicians and advisor links must be listable before those people ever sign in.

CREATE TABLE app_user_identity (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    provider       VARCHAR(16) NOT NULL,
    persistent_id  VARCHAR(120) NOT NULL,
    linked_at      TIMESTAMPTZ NOT NULL,
    last_login_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_app_user_identity UNIQUE (provider, persistent_id),
    CONSTRAINT uq_app_user_identity_user UNIQUE (user_id, provider)
);

CREATE TABLE institution_person (
    id                 UUID PRIMARY KEY,
    institution        VARCHAR(16) NOT NULL,
    persistent_id      VARCHAR(120) NOT NULL,
    user_id            UUID REFERENCES app_user (id) ON DELETE SET NULL,
    student            BOOLEAN NOT NULL DEFAULT FALSE,
    academic           BOOLEAN NOT NULL DEFAULT FALSE,
    staff              BOOLEAN NOT NULL DEFAULT FALSE,
    full_name          VARCHAR(255) NOT NULL,
    email              VARCHAR(255),
    faculty            VARCHAR(255),
    department_code    VARCHAR(32),
    department_name    VARCHAR(255),
    class_year         INTEGER,
    program_level      VARCHAR(16),
    academic_title     VARCHAR(64),
    iskur_participant  BOOLEAN NOT NULL DEFAULT FALSE,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    source             VARCHAR(16) NOT NULL,
    synced_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_institution_person UNIQUE (institution, persistent_id),
    CONSTRAINT chk_institution_person_program_level
        CHECK (program_level IS NULL OR program_level IN ('LISANS', 'YUKSEK_LISANS', 'DOKTORA')),
    CONSTRAINT chk_institution_person_source CHECK (source IN ('LOGIN', 'IMPORT', 'DEV_STUB'))
);

CREATE INDEX ix_institution_person_user ON institution_person (user_id);

CREATE TABLE institution_advising (
    id                     UUID PRIMARY KEY,
    institution            VARCHAR(16) NOT NULL,
    student_persistent_id  VARCHAR(120) NOT NULL,
    advisor_persistent_id  VARCHAR(120) NOT NULL,
    synced_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_institution_advising UNIQUE (institution, student_persistent_id, advisor_persistent_id)
);

CREATE INDEX ix_institution_advising_advisor ON institution_advising (institution, advisor_persistent_id);
