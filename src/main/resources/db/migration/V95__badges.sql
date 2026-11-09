-- Menton's own badges. The gate badge "Akran Mentoru" (PEER_MENTOR) grants the MENTOR permission in a peer
-- program; achievement badges are shown on profiles. The catalogue lives in code (BadgeCode), names and texts
-- in the message files. An award is never deleted: revoking or expiring changes its status in place.
-- V95 is provisional (main ends V91; V92–V94 are proposed for Office Hours); re-check before merging.

CREATE TABLE user_badge (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    badge_code  VARCHAR(48) NOT NULL,
    program_id  UUID REFERENCES program (id) ON DELETE SET NULL,
    status      VARCHAR(16) NOT NULL,
    source      VARCHAR(16) NOT NULL,
    awarded_at  TIMESTAMPTZ NOT NULL,
    awarded_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    revoked_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    reason      TEXT,
    CONSTRAINT chk_user_badge_code CHECK (badge_code IN
        ('PEER_MENTOR', 'FIRST_SESSION', 'SESSIONS_10', 'HOURS_25', 'WELL_RATED', 'ACTIVE_MENTEE')),
    CONSTRAINT chk_user_badge_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT chk_user_badge_source CHECK (source IN ('RULE', 'ADMIN', 'COORDINATOR')),
    CONSTRAINT chk_user_badge_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE INDEX ix_user_badge_user ON user_badge (user_id);
CREATE INDEX ix_user_badge_program ON user_badge (program_id, badge_code);

-- At most one active award per person, badge and program (a platform-wide badge has no program).
CREATE UNIQUE INDEX uq_user_badge_active ON user_badge
    (user_id, badge_code, COALESCE(program_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';
