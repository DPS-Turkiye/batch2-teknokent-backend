-- When a mentorship began: the moment its request was first accepted (Mentorluklarım shows it as the start date).
-- Requests accepted before this column existed get their last update, the closest record there is.
ALTER TABLE mentorship_request ADD COLUMN accepted_at TIMESTAMPTZ;

UPDATE mentorship_request SET accepted_at = updated_at WHERE status = 'ACCEPTED';
