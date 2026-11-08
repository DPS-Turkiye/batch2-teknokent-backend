-- A mentor's schools, read from the CV or typed in, shown apart from the work experiences. Optional for everyone.
CREATE TABLE mentor_education (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    school VARCHAR(200) NOT NULL,
    degree VARCHAR(200),
    period VARCHAR(100),
    PRIMARY KEY (mentor_id, display_order)
);
