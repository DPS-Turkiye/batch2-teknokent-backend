-- A personal team is the hidden team of one an ODTÜ student gets at sign-in, so org-keyed requests, meetings and
-- session hours work for them. It is never listed as a team and never shown as a startup.
ALTER TABLE organization ADD COLUMN personal BOOLEAN NOT NULL DEFAULT FALSE;
