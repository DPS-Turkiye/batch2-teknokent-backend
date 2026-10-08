-- What the mentee wants to leave the meeting with ("BEKLENEN ÇIKTI"); older rows keep it inside requester_message.
ALTER TABLE mentorship_request ADD COLUMN expected_outcome TEXT;
