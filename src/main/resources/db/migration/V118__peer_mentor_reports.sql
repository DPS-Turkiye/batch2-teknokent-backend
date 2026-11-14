-- İŞKUR peer-mentor reporting: a meeting report per meeting per mentee, the activities outside meetings, a daily
-- report per working day and a weekly summary per week. Meeting reports and activities flow into the day, the days
-- into the week, so nothing is written twice. All three report levels share one review life cycle
-- (DRAFT -> IN_REVIEW -> APPROVED / REVISION_REQUESTED). Only the mentor and the program's coordinators see them;
-- the mentee never does. Coordinator support is a yes/no flag on purpose: no sensitive mentee detail is stored.

CREATE TABLE peer_meeting_report (
    id                          UUID PRIMARY KEY,
    meeting_id                  UUID         NOT NULL REFERENCES meeting (id) ON DELETE CASCADE,
    mentor_id                   UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    mentee_id                   UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    program_id                  UUID         REFERENCES program (id) ON DELETE SET NULL,
    meeting_type                VARCHAR(16),
    meeting_format              VARCHAR(16),
    actual_duration_minutes     INTEGER,
    mentee_need                 TEXT,
    discussion                  TEXT,
    approach                    TEXT,
    skill_example               TEXT,
    outcome                     TEXT,
    follow_up_needed            BOOLEAN      NOT NULL DEFAULT FALSE,
    follow_up_at                TIMESTAMPTZ,
    productivity                INTEGER,
    next_time                   TEXT,
    coordinator_support_needed  BOOLEAN,
    status                      VARCHAR(24)  NOT NULL,
    submitted_at                TIMESTAMPTZ,
    reviewed_at                 TIMESTAMPTZ,
    reviewed_by                 UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    revision_note               TEXT,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    version                     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_peer_meeting_report_meeting_mentee UNIQUE (meeting_id, mentee_id),
    CONSTRAINT chk_peer_meeting_report_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REVISION_REQUESTED')),
    CONSTRAINT chk_peer_meeting_report_type CHECK (meeting_type IS NULL OR meeting_type IN ('FIRST', 'FOLLOW_UP')),
    CONSTRAINT chk_peer_meeting_report_format CHECK (meeting_format IS NULL OR meeting_format IN ('IN_PERSON', 'ONLINE')),
    CONSTRAINT chk_peer_meeting_report_productivity CHECK (productivity IS NULL OR productivity BETWEEN 1 AND 5),
    CONSTRAINT chk_peer_meeting_report_duration CHECK (
        actual_duration_minutes IS NULL OR actual_duration_minutes BETWEEN 1 AND 600)
);

CREATE INDEX idx_peer_meeting_report_mentor ON peer_meeting_report (mentor_id, created_at);
CREATE INDEX idx_peer_meeting_report_mentee ON peer_meeting_report (mentee_id);
CREATE INDEX idx_peer_meeting_report_review ON peer_meeting_report (program_id, status);

CREATE TABLE peer_meeting_report_topic (
    report_id UUID        NOT NULL REFERENCES peer_meeting_report (id) ON DELETE CASCADE,
    topic     VARCHAR(40) NOT NULL,
    PRIMARY KEY (report_id, topic)
);

CREATE TABLE peer_meeting_report_skill (
    report_id UUID        NOT NULL REFERENCES peer_meeting_report (id) ON DELETE CASCADE,
    skill     VARCHAR(40) NOT NULL,
    PRIMARY KEY (report_id, skill)
);

-- "Eylem adımları": who does what by when. Ticking one off stays possible after the report is sent.
CREATE TABLE peer_meeting_report_action (
    id         UUID PRIMARY KEY,
    report_id  UUID         NOT NULL REFERENCES peer_meeting_report (id) ON DELETE CASCADE,
    position   INTEGER      NOT NULL,
    text       VARCHAR(500) NOT NULL,
    owner      VARCHAR(16)  NOT NULL,
    due_date   DATE,
    done_at    TIMESTAMPTZ,
    CONSTRAINT chk_peer_meeting_report_action_owner CHECK (owner IN ('MENTEE', 'MENTOR'))
);

CREATE INDEX idx_peer_meeting_report_action_report ON peer_meeting_report_action (report_id, position);

-- Mentoring work outside meetings (resource preparation, follow-up messages, events), added to a day with a duration.
-- The day's report finds them by (mentor, date); they are not copied into it.
CREATE TABLE peer_activity (
    id                UUID PRIMARY KEY,
    mentor_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    program_id        UUID         REFERENCES program (id) ON DELETE SET NULL,
    activity_date     DATE         NOT NULL,
    activity_type     VARCHAR(24)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    duration_minutes  INTEGER      NOT NULL,
    mentee_id         UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    description       TEXT,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_peer_activity_type CHECK (activity_type IN ('RESOURCE_PREP', 'MESSAGE_FOLLOW_UP', 'EVENT', 'OTHER')),
    CONSTRAINT chk_peer_activity_duration CHECK (duration_minutes BETWEEN 1 AND 720)
);

CREATE INDEX idx_peer_activity_mentor_date ON peer_activity (mentor_id, activity_date);

CREATE TABLE peer_daily_report (
    id             UUID PRIMARY KEY,
    mentor_id      UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    program_id     UUID         REFERENCES program (id) ON DELETE SET NULL,
    report_date    DATE         NOT NULL,
    did_today      TEXT,
    challenge      TEXT,
    learned        TEXT,
    tomorrow_plan  TEXT,
    status         VARCHAR(24)  NOT NULL,
    submitted_at   TIMESTAMPTZ,
    reviewed_at    TIMESTAMPTZ,
    reviewed_by    UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    revision_note  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_peer_daily_report_mentor_date UNIQUE (mentor_id, report_date),
    CONSTRAINT chk_peer_daily_report_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REVISION_REQUESTED'))
);

CREATE INDEX idx_peer_daily_report_review ON peer_daily_report (program_id, status);

CREATE TABLE peer_daily_report_skill (
    report_id UUID        NOT NULL REFERENCES peer_daily_report (id) ON DELETE CASCADE,
    skill     VARCHAR(40) NOT NULL,
    PRIMARY KEY (report_id, skill)
);

-- The numbers, skills and per-mentee outcomes are computed from the meeting reports and activities; the snapshot
-- freezes them when the week is sent, so the coordinator reviews exactly what the mentor saw.
CREATE TABLE peer_weekly_report (
    id                UUID PRIMARY KEY,
    mentor_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    program_id        UUID         REFERENCES program (id) ON DELETE SET NULL,
    week_start        DATE         NOT NULL,
    needs_observed    TEXT,
    growth            TEXT,
    next_week_plan    TEXT,
    summary_snapshot  JSONB,
    status            VARCHAR(24)  NOT NULL,
    submitted_at      TIMESTAMPTZ,
    reviewed_at       TIMESTAMPTZ,
    reviewed_by       UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    revision_note     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_peer_weekly_report_mentor_week UNIQUE (mentor_id, week_start),
    CONSTRAINT chk_peer_weekly_report_status CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REVISION_REQUESTED'))
);

CREATE INDEX idx_peer_weekly_report_review ON peer_weekly_report (program_id, status);
