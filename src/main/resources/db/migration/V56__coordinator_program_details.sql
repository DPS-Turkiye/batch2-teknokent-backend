ALTER TABLE program
    ADD COLUMN description TEXT,
    ADD COLUMN start_date DATE,
    ADD COLUMN end_date DATE,
    ADD COLUMN meeting_frequency_days INTEGER NOT NULL DEFAULT 14,
    ADD COLUMN stale_after_days INTEGER NOT NULL DEFAULT 14,
    ADD COLUMN meeting_note_required BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN auto_match_suggestions BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT chk_program_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    ADD CONSTRAINT chk_program_meeting_frequency CHECK (meeting_frequency_days BETWEEN 1 AND 90),
    ADD CONSTRAINT chk_program_stale_after CHECK (stale_after_days BETWEEN 1 AND 180);

CREATE TABLE program_phase (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    position INTEGER NOT NULL,
    start_date DATE,
    end_date DATE,
    CONSTRAINT uq_program_phase_position UNIQUE (program_id, position),
    CONSTRAINT chk_program_phase_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE program_event (
    id UUID PRIMARY KEY,
    program_id UUID NOT NULL REFERENCES program(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    description TEXT,
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_program_event_kind CHECK (kind IN ('EVENT', 'DEADLINE')),
    CONSTRAINT chk_program_event_times CHECK (ends_at IS NULL OR ends_at >= starts_at)
);

CREATE INDEX idx_program_event_program_starts ON program_event (program_id, starts_at);
CREATE INDEX idx_program_event_created_by ON program_event (created_by);
