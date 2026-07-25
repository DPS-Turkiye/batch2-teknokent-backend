CREATE TABLE health_probe (
    id SMALLINT PRIMARY KEY,
    message VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO health_probe (id, message) VALUES (1, 'ok');
