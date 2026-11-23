-- E-mails a coordinator or an admin writes to the people of a program, with the exact recipient list they reviewed
-- and what became of each mail. An account's name and address leave the log when the account is deleted.
SET LOCAL lock_timeout = '5s';

CREATE TABLE email_campaign (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id),
    created_by UUID NOT NULL REFERENCES app_user(id),
    audience VARCHAR(16) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body_html TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    test_sent_at TIMESTAMPTZ,
    review_digest VARCHAR(64),
    reviewed_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    -- The end of the hold: the first mail leaves no earlier, and the confirmed mail can be stopped until then.
    starts_at TIMESTAMPTZ,
    stopped_at TIMESTAMPTZ,
    stopped_by UUID REFERENCES app_user(id),
    finished_at TIMESTAMPTZ,
    alerted_problems INTEGER NOT NULL DEFAULT 0,
    last_alert_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_email_campaign_audience
        CHECK (audience IN ('PARTICIPANTS', 'MENTORS', 'EVERYONE', 'UNJOINED')),
    CONSTRAINT chk_email_campaign_status CHECK (status IN ('DRAFT', 'SENDING', 'SENT', 'STOPPED'))
);

CREATE INDEX idx_email_campaign_created_by ON email_campaign (created_by, created_at DESC);
CREATE INDEX idx_email_campaign_program ON email_campaign (program_id);
CREATE INDEX idx_email_campaign_created_at ON email_campaign (created_at DESC);
CREATE INDEX idx_email_campaign_sending ON email_campaign (id) WHERE status = 'SENDING';

CREATE TABLE email_campaign_recipient (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES email_campaign(id) ON DELETE CASCADE,
    -- Null for a person a founder listed who has no account.
    user_id UUID REFERENCES app_user(id),
    -- No foreign keys: the team and the founder's list may lose the row while the log stays.
    organization_id UUID,
    pending_member_id UUID,
    kind VARCHAR(16) NOT NULL,
    address VARCHAR(320),
    full_name VARCHAR(255) NOT NULL,
    team_name VARCHAR(255),
    account_status VARCHAR(16),
    status VARCHAR(16) NOT NULL,
    exclusion VARCHAR(32),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    ses_message_id VARCHAR(128),
    error_code VARCHAR(64),
    unsubscribe_token_hash VARCHAR(64),
    CONSTRAINT uq_email_campaign_recipient_address UNIQUE (campaign_id, address),
    CONSTRAINT uq_email_campaign_recipient_message UNIQUE (ses_message_id),
    CONSTRAINT uq_email_campaign_recipient_token UNIQUE (unsubscribe_token_hash),
    CONSTRAINT chk_email_campaign_recipient_kind CHECK (kind IN ('PARTICIPANT', 'MENTOR', 'UNJOINED')),
    CONSTRAINT chk_email_campaign_recipient_exclusion CHECK (exclusion IN ('ACCOUNT_NOT_APPROVED', 'NO_ADDRESS',
        'NOT_ELIGIBLE', 'UNSUBSCRIBED', 'UNDELIVERABLE', 'NOT_ON_ALLOWLIST')),
    CONSTRAINT chk_email_campaign_recipient_status CHECK (status IN ('EXCLUDED', 'REVIEWED', 'UNTICKED', 'PENDING',
        'SENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'COMPLAINED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_email_campaign_recipient_user ON email_campaign_recipient (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_email_campaign_recipient_organization ON email_campaign_recipient (organization_id)
    WHERE organization_id IS NOT NULL;
CREATE INDEX idx_email_campaign_recipient_due ON email_campaign_recipient (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_email_campaign_recipient_claimed ON email_campaign_recipient (claimed_at)
    WHERE status = 'SENDING';
CREATE INDEX idx_email_campaign_recipient_campaign_status ON email_campaign_recipient (campaign_id, status);
CREATE INDEX idx_email_campaign_recipient_address ON email_campaign_recipient (address, sent_at DESC)
    WHERE address IS NOT NULL AND sent_at IS NOT NULL;

-- Addresses the email center no longer writes to, as SHA-256 only. Kept after an account is deleted, so that an
-- unsubscribed address stays unsubscribed. Account mails (passwords, approvals, matches) never read this table.
CREATE TABLE email_suppression (
    id UUID PRIMARY KEY,
    address_hash VARCHAR(64) NOT NULL,
    reason VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_email_suppression_address UNIQUE (address_hash),
    CONSTRAINT chk_email_suppression_reason CHECK (reason IN ('UNSUBSCRIBED', 'HARD_BOUNCE', 'COMPLAINT'))
);
