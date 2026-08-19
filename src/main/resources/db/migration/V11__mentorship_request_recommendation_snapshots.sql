CREATE TABLE mentorship_request_recommendation_run (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE REFERENCES mentorship_request(id) ON DELETE CASCADE,
    matching_method VARCHAR(64) NOT NULL,
    matching_model VARCHAR(255),
    generated_at TIMESTAMPTZ NOT NULL,
    startup_name TEXT,
    startup_sector TEXT,
    startup_stage TEXT,
    startup_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mentorship_request_recommendation_technology (
    run_id UUID NOT NULL REFERENCES mentorship_request_recommendation_run(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    technology VARCHAR(255) NOT NULL,
    PRIMARY KEY (run_id, display_order)
);

CREATE TABLE mentorship_request_recommendation_item (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES mentorship_request_recommendation_run(id) ON DELETE CASCADE,
    mentor_id UUID NOT NULL REFERENCES app_user(id),
    rank INTEGER NOT NULL,
    selected BOOLEAN NOT NULL,
    mentor_name TEXT NOT NULL,
    professional_title TEXT NOT NULL,
    company TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_mentorship_request_recommendation_rank UNIQUE (run_id, rank),
    CONSTRAINT uq_mentorship_request_recommendation_mentor UNIQUE (run_id, mentor_id),
    CONSTRAINT chk_mentorship_request_recommendation_rank CHECK (rank > 0)
);

CREATE TABLE mentorship_request_recommendation_reason (
    item_id UUID NOT NULL REFERENCES mentorship_request_recommendation_item(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    PRIMARY KEY (item_id, display_order)
);

CREATE INDEX idx_mentorship_request_recommendation_run_generated
    ON mentorship_request_recommendation_run(generated_at DESC);

CREATE INDEX idx_mentorship_request_recommendation_item_mentor_selected
    ON mentorship_request_recommendation_item(mentor_id, selected, created_at DESC);
