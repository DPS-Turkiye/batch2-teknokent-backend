-- One account can hold several permissions (mentee, peer mentor, academician, coordinator, admin).
-- app_user.role stays for one release as the primary role; every account starts with that one grant.

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_app_user_role;
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'COORDINATOR', 'MENTOR', 'MENTEE', 'ACADEMICIAN'));

CREATE TABLE app_user_permission (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    permission  VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    source      VARCHAR(16) NOT NULL,
    source_ref  UUID,
    granted_at  TIMESTAMPTZ NOT NULL,
    granted_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    revoked_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_app_user_permission UNIQUE (user_id, permission),
    CONSTRAINT chk_app_user_permission_permission
        CHECK (permission IN ('ADMIN', 'COORDINATOR', 'MENTOR', 'MENTEE', 'ACADEMICIAN')),
    CONSTRAINT chk_app_user_permission_status CHECK (status IN ('ACTIVE', 'PENDING', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_app_user_permission_source
        CHECK (source IN ('LEGACY_ROLE', 'REGISTRATION', 'INSTITUTION', 'BADGE', 'ADMIN'))
);

INSERT INTO app_user_permission (id, user_id, permission, status, source, granted_at)
SELECT gen_random_uuid(), id, role, 'ACTIVE', 'LEGACY_ROLE', created_at
FROM app_user;
