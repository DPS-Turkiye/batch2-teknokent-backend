CREATE TABLE assistant_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform VARCHAR(32) NOT NULL,
    native_meeting_id VARCHAR(255) NOT NULL,
    owner_user_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
    meeting_id UUID REFERENCES meeting(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_assistant_session_provider_meeting UNIQUE (platform, native_meeting_id),
    CONSTRAINT ck_assistant_session_owner CHECK (owner_user_id IS NOT NULL OR meeting_id IS NOT NULL)
);

CREATE INDEX ix_assistant_session_owner_created ON assistant_session(owner_user_id, created_at);
CREATE INDEX ix_assistant_session_meeting ON assistant_session(meeting_id);

CREATE TABLE assistant_session_lock (
    lock_key VARCHAR(300) PRIMARY KEY
);

-- Keep sessions for bots already dispatched by the scheduler addressable through meeting ownership.
INSERT INTO assistant_session (platform, native_meeting_id, meeting_id)
SELECT DISTINCT ON (lower((regexp_match(meet_link, '([a-z]{3}-[a-z]{4}-[a-z]{3})/?(?:\?.*)?$'))[1]))
       'google_meet',
       lower((regexp_match(meet_link, '([a-z]{3}-[a-z]{4}-[a-z]{3})/?(?:\?.*)?$'))[1]),
       id
FROM meeting
WHERE bot_dispatched_at IS NOT NULL
  AND meet_link IS NOT NULL
  AND meet_link ~* '^https://meet\.google\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}/?(\?.*)?$'
ORDER BY lower((regexp_match(meet_link, '([a-z]{3}-[a-z]{4}-[a-z]{3})/?(?:\?.*)?$'))[1]), scheduled_at DESC;

CREATE TABLE assistant_report_generation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_assistant_report_actor_created ON assistant_report_generation(actor_user_id, created_at);
