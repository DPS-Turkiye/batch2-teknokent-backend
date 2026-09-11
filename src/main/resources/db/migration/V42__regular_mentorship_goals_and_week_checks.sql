ALTER TABLE mentorship
    ADD COLUMN days_per_week INT NOT NULL DEFAULT 1;

ALTER TABLE mentorship
    ADD CONSTRAINT chk_mentorship_days_per_week
        CHECK (days_per_week >= 1 AND days_per_week <= 7);

CREATE TABLE mentorship_goal (
    id UUID PRIMARY KEY,
    mentorship_id UUID NOT NULL REFERENCES mentorship(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentorship_goal_title CHECK (char_length(trim(title)) > 0)
);

CREATE INDEX idx_mentorship_goal_mentorship
    ON mentorship_goal(mentorship_id, sort_order);

CREATE TABLE mentorship_week_check (
    id UUID PRIMARY KEY,
    mentorship_id UUID NOT NULL REFERENCES mentorship(id) ON DELETE CASCADE,
    week_start DATE NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_mentorship_week_check UNIQUE (mentorship_id, week_start)
);

CREATE INDEX idx_mentorship_week_check_mentor_week
    ON mentorship_week_check(week_start);
