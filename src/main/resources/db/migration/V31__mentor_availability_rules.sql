-- Mentors set availability as a recurring weekly window ("Salı 14:00-17:00")
-- rather than one slot at a time. Rules are materialised into concrete
-- mentor_availability_slot rows so the existing mentee booking flow, which
-- books a specific slot, keeps working unchanged.
CREATE TABLE mentor_availability_rule (
    id UUID PRIMARY KEY,
    mentor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    -- ISO-8601: Monday = 1 ... Sunday = 7, matching java.time.DayOfWeek.
    day_of_week SMALLINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    slot_minutes INTEGER NOT NULL DEFAULT 60,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mentor_availability_rule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_mentor_availability_rule_window CHECK (end_time > start_time),
    CONSTRAINT chk_mentor_availability_rule_slot CHECK (slot_minutes > 0),
    CONSTRAINT uq_mentor_availability_rule UNIQUE (mentor_id, day_of_week, start_time)
);

-- Provenance, so removing a rule can withdraw the future slots it generated
-- while leaving anything a mentee already booked untouched.
ALTER TABLE mentor_availability_slot
    ADD COLUMN rule_id UUID REFERENCES mentor_availability_rule(id) ON DELETE SET NULL;

CREATE INDEX idx_mentor_availability_rule_mentor ON mentor_availability_rule (mentor_id);
CREATE INDEX idx_mentor_availability_slot_rule ON mentor_availability_slot (rule_id);
