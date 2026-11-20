-- A founder can delete their startup. Its deletion is recorded in user_deletion_audit next to account deletions, so
-- the files it leaves to remove from storage go through the same retried cleanup queue (user_deletion_cleanup).
-- A row names either a deleted account or a deleted startup, never both. Still ids only and no foreign keys.
-- Fail fast instead of queueing the app behind a long-held lock during the deploy; the migration can be retried.
SET LOCAL lock_timeout = '5s';

ALTER TABLE user_deletion_audit ALTER COLUMN deleted_user_id DROP NOT NULL;
ALTER TABLE user_deletion_audit ADD COLUMN deleted_organization_id UUID;
ALTER TABLE user_deletion_audit
    ADD CONSTRAINT chk_user_deletion_audit_subject
        CHECK (num_nonnulls(deleted_user_id, deleted_organization_id) = 1);

CREATE INDEX idx_user_deletion_audit_deleted_organization
    ON user_deletion_audit (deleted_organization_id)
    WHERE deleted_organization_id IS NOT NULL;
