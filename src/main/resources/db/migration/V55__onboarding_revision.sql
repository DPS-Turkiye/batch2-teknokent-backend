UPDATE organization SET maturity = CASE maturity
        WHEN 'Fikir aşaması' THEN 'Fikir'
        WHEN 'MVP geliştiriliyor' THEN 'Prototip'
        WHEN 'MVP yayında' THEN 'MVP'
        WHEN 'Gelir elde ediyor' THEN 'Gelir'
        ELSE maturity
    END
WHERE maturity IS NOT NULL;

ALTER TABLE organization
    ADD COLUMN mvp_url VARCHAR(255);

CREATE TABLE app_user_contact_preference (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    preference VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, display_order),
    CONSTRAINT chk_app_user_contact_preference
        CHECK (preference IN ('IN_APP_MESSAGE', 'VIDEO_CALL', 'EMAIL', 'IN_PERSON'))
);

CREATE TABLE organization_pending_member (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    full_name VARCHAR(255) NOT NULL,
    job_title VARCHAR(255),
    email VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_organization_pending_member_organization
    ON organization_pending_member (organization_id);

CREATE TABLE mentor_offering (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    offering VARCHAR(255) NOT NULL,
    PRIMARY KEY (mentor_id, display_order)
);
