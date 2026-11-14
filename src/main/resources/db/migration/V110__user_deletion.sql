-- An admin can delete a user. The account row stays as a tombstone with no personal data (status DELETED), so the
-- history shared with other people keeps its links and shows "Silinmiş kullanıcı".
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_app_user_status;
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DELETED'));

-- Who deleted whom and when. Ids only: no name, e-mail or any other personal data, and no foreign keys, so a row
-- outlives every account it names.
CREATE TABLE user_deletion_audit (
    id                   UUID PRIMARY KEY,
    deleted_user_id      UUID        NOT NULL,
    deleted_by_id        UUID        NOT NULL,
    deleted_at           TIMESTAMPTZ NOT NULL,
    cleanup_completed_at TIMESTAMPTZ
);

CREATE INDEX idx_user_deletion_audit_deleted_user ON user_deletion_audit (deleted_user_id);

-- What is still to be removed outside the database after a deletion committed: the Firebase login and the stored
-- files. A row is deleted as soon as its target is gone; until then the scheduler retries it.
CREATE TABLE user_deletion_cleanup (
    id              UUID PRIMARY KEY,
    audit_id        UUID         NOT NULL REFERENCES user_deletion_audit (id) ON DELETE CASCADE,
    kind            VARCHAR(32)  NOT NULL,
    target          VARCHAR(512) NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_user_deletion_cleanup_kind CHECK (kind IN ('FIREBASE_LOGIN', 'STORED_OBJECT'))
);

CREATE INDEX idx_user_deletion_cleanup_due ON user_deletion_cleanup (next_attempt_at);

-- A deleted account's row never changes again. Without this, a request that read the account just before it was
-- deleted and saves it just after (Hibernate writes every column) would put the old name, address, uid and
-- status back. The deletion itself is not caught: when it runs, the old status is not DELETED yet.
CREATE FUNCTION refuse_change_to_deleted_app_user() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'app_user % was deleted and cannot be changed', OLD.id USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_app_user_deleted_is_final
    BEFORE UPDATE ON app_user
    FOR EACH ROW
    WHEN (OLD.status = 'DELETED')
    EXECUTE FUNCTION refuse_change_to_deleted_app_user();
