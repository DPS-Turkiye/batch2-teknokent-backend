-- When the team code was last emailed to the lead, and whether SES refused that send ("Sorunlu" in Takımlar).
ALTER TABLE program_team_invite
    ADD COLUMN code_sent_at TIMESTAMPTZ,
    ADD COLUMN code_send_failed BOOLEAN NOT NULL DEFAULT FALSE;
