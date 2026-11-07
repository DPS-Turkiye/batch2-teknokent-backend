-- The mentor's own "Görüşme yapıldı" on a past meeting. Only confirmed meetings count in the program's
-- sessions-and-hours report (İŞKUR pays student mentors per day worked). COMPLETED is not enough: only the
-- online meeting bot sets it, so face-to-face meetings never reach it.
ALTER TABLE meeting ADD COLUMN held_confirmed_at TIMESTAMPTZ;
