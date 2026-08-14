CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE mentor_profile_embedding (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(160) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    embedding vector NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (mentor_id, provider, model)
);

CREATE INDEX idx_mentor_profile_embedding_model
    ON mentor_profile_embedding(provider, model);
