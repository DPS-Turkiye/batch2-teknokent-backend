ALTER TABLE mentor_profile ADD COLUMN mentee_capacity INTEGER;
ALTER TABLE mentor_profile ADD CONSTRAINT chk_mentor_profile_mentee_capacity
    CHECK (mentee_capacity IS NULL OR mentee_capacity BETWEEN 1 AND 50);

CREATE TABLE mentor_experience (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    organization VARCHAR(200),
    period VARCHAR(100),
    summary VARCHAR(300),
    PRIMARY KEY (mentor_id, display_order)
);
