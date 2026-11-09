-- The Akran Mentoru training (4T): one per peer program, a video to watch and a 10-question quiz. Passing it
-- awards the gate badge (user_badge, V95, source RULE). Editing makes a new version; progress and attempts
-- belong to a version, and attempts are never deleted. The correct option is read only by the backend.

CREATE TABLE training_module (
    id               UUID PRIMARY KEY,
    program_id       UUID NOT NULL UNIQUE REFERENCES program (id) ON DELETE CASCADE,
    current_version  INTEGER NOT NULL,
    video_id         VARCHAR(32) NOT NULL,
    published_at     TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    updated_by       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_training_module_version CHECK (current_version >= 1)
);

CREATE TABLE training_question (
    id              UUID PRIMARY KEY,
    module_id       UUID NOT NULL REFERENCES training_module (id) ON DELETE CASCADE,
    version         INTEGER NOT NULL,
    position        INTEGER NOT NULL,
    text            TEXT NOT NULL,
    option_1        TEXT NOT NULL,
    option_2        TEXT NOT NULL,
    option_3        TEXT NOT NULL,
    option_4        TEXT NOT NULL,
    correct_option  SMALLINT NOT NULL,
    CONSTRAINT uq_training_question UNIQUE (module_id, version, position),
    CONSTRAINT chk_training_question_correct CHECK (correct_option BETWEEN 0 AND 3)
);

CREATE TABLE training_progress (
    id                  UUID PRIMARY KEY,
    module_id           UUID NOT NULL REFERENCES training_module (id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    version             INTEGER NOT NULL,
    video_completed_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_training_progress UNIQUE (module_id, user_id, version)
);

CREATE TABLE training_attempt (
    id            UUID PRIMARY KEY,
    module_id     UUID NOT NULL REFERENCES training_module (id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    version       INTEGER NOT NULL,
    score         INTEGER NOT NULL,
    passed        BOOLEAN NOT NULL,
    answers       TEXT NOT NULL,
    submitted_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_training_attempt_score CHECK (score BETWEEN 0 AND 10)
);

CREATE INDEX ix_training_attempt_user ON training_attempt (module_id, user_id, submitted_at);
