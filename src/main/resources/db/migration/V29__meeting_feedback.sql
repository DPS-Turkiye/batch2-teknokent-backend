CREATE TABLE meeting_feedback (
    id UUID PRIMARY KEY,
    meeting_id UUID NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT meeting_feedback_one_per_author UNIQUE (meeting_id, author_id)
);

CREATE TABLE meeting_feedback_rating (
    feedback_id UUID NOT NULL REFERENCES meeting_feedback(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    PRIMARY KEY (feedback_id, display_order)
);

CREATE TABLE meeting_feedback_benefit (
    feedback_id UUID NOT NULL REFERENCES meeting_feedback(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    benefit VARCHAR(255) NOT NULL,
    PRIMARY KEY (feedback_id, display_order)
);

CREATE INDEX idx_meeting_feedback_meeting ON meeting_feedback (meeting_id);
