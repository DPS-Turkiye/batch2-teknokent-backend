-- V73 and V77 put invented mentors, founders and startups into production for a presentation and for testing.
-- None of them is a real user, so they come out here, together with everything made with them since (requests,
-- meetings, messages, documents). Do not edit V73/V77: they already ran.
-- The Growth Circuit 2026 program stays (empty), and so do the V60 admin and the V69 coordinator.
-- Files in S3 behind deleted stored_document rows are not removed by this migration.

CREATE TABLE seed_person (id UUID PRIMARY KEY);
CREATE TABLE seed_startup (id UUID PRIMARY KEY);

INSERT INTO seed_person (id) VALUES
    ('c1a00000-0000-4000-8000-000000000001'),
    ('c1a00000-0000-4000-8000-000000000002'),
    ('c1a00000-0000-4000-8000-000000000003'),
    ('c1a00000-0000-4000-8000-000000000004'),
    ('c1a00000-0000-4000-8000-000000000005'),
    ('c1a00000-0000-4000-8000-000000000011'),
    ('c1a00000-0000-4000-8000-000000000012'),
    ('c1a00000-0000-4000-8000-000000000013'),
    ('c1a00000-0000-4000-8000-000000000014'),
    ('c1a00000-0000-4000-8000-000000000015'),
    ('c3da0000-0000-4000-8000-000000000001'),
    ('c3da0000-0000-4000-8000-000000000011');

INSERT INTO seed_startup (id) VALUES
    ('c1a00000-0000-4000-8000-000000000021'),
    ('c1a00000-0000-4000-8000-000000000022'),
    ('c1a00000-0000-4000-8000-000000000023'),
    ('c1a00000-0000-4000-8000-000000000024'),
    ('c1a00000-0000-4000-8000-000000000025'),
    ('c3da0000-0000-4000-8000-000000000021');

DELETE FROM onboarding_draft
WHERE firebase_uid IN (
    SELECT firebase_uid FROM app_user
    WHERE id IN (SELECT id FROM seed_person) AND firebase_uid IS NOT NULL
);

UPDATE mentorship_intervention
SET notification_id = NULL
WHERE notification_id IN (
    SELECT id FROM notification WHERE recipient_id IN (SELECT id FROM seed_person)
);

DELETE FROM program_mentor_invite
WHERE mentor_id IN (SELECT id FROM seed_person)
   OR email IN (SELECT email FROM app_user WHERE id IN (SELECT id FROM seed_person));

DELETE FROM program_team_invite
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR lead_email IN (SELECT email FROM app_user WHERE id IN (SELECT id FROM seed_person));

DELETE FROM program_mentor
WHERE mentor_id IN (SELECT id FROM seed_person);

DELETE FROM program_startup
WHERE organization_id IN (SELECT id FROM seed_startup);

DELETE FROM meeting_proposal
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR mentor_id IN (SELECT id FROM seed_person)
   OR proposed_by IN (SELECT id FROM seed_person)
   OR declined_by IN (SELECT id FROM seed_person)
   OR team_responder_id IN (SELECT id FROM seed_person);

CREATE TABLE seed_meeting (id UUID PRIMARY KEY);
INSERT INTO seed_meeting (id)
SELECT id FROM meeting
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR mentor_id IN (SELECT id FROM seed_person);

DELETE FROM action_item
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR owner_id IN (SELECT id FROM seed_person)
   OR meeting_id IN (SELECT id FROM seed_meeting);

DELETE FROM bottleneck
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR owner_id IN (SELECT id FROM seed_person);

DELETE FROM progress_update
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR author_id IN (SELECT id FROM seed_person);

DELETE FROM document_request_upload
WHERE uploaded_by_id IN (SELECT id FROM seed_person)
   OR stored_document_id IN (
        SELECT id FROM stored_document
        WHERE user_id IN (SELECT id FROM seed_person)
           OR organization_id IN (SELECT id FROM seed_startup)
   )
   OR item_id IN (
        SELECT id FROM document_request_item
        WHERE organization_id IN (SELECT id FROM seed_startup)
           OR created_by_id IN (SELECT id FROM seed_person)
           OR meeting_id IN (SELECT id FROM seed_meeting)
   );

DELETE FROM document_request_item
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR created_by_id IN (SELECT id FROM seed_person)
   OR meeting_id IN (SELECT id FROM seed_meeting);

DELETE FROM meeting_feedback
WHERE author_id IN (SELECT id FROM seed_person)
   OR meeting_id IN (SELECT id FROM seed_meeting);

UPDATE meeting_note
SET updated_by_id = NULL
WHERE updated_by_id IN (SELECT id FROM seed_person);

UPDATE meeting_todo
SET created_by_id = NULL
WHERE created_by_id IN (SELECT id FROM seed_person);

UPDATE meeting_assistant_share
SET published_by_id = NULL
WHERE published_by_id IN (SELECT id FROM seed_person);

DELETE FROM meeting
WHERE id IN (SELECT id FROM seed_meeting);

DELETE FROM stored_document_share
WHERE shared_by_id IN (SELECT id FROM seed_person);

DELETE FROM stored_document
WHERE user_id IN (SELECT id FROM seed_person)
   OR organization_id IN (SELECT id FROM seed_startup);

DELETE FROM profile_analysis
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR app_user_id IN (SELECT id FROM seed_person);

DELETE FROM startup_round
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR created_by_id IN (SELECT id FROM seed_person);

UPDATE meeting
SET mentorship_request_id = NULL
WHERE mentorship_request_id IN (
    SELECT id FROM mentorship_request
    WHERE requester_id IN (SELECT id FROM seed_person)
       OR mentor_id IN (SELECT id FROM seed_person)
       OR organization_id IN (SELECT id FROM seed_startup)
);

DELETE FROM mentorship_request_recommendation_item
WHERE mentor_id IN (SELECT id FROM seed_person);

DELETE FROM mentorship_request_message
WHERE author_id IN (SELECT id FROM seed_person);

DELETE FROM mentorship_request
WHERE requester_id IN (SELECT id FROM seed_person)
   OR mentor_id IN (SELECT id FROM seed_person)
   OR organization_id IN (SELECT id FROM seed_startup);

DELETE FROM coordinator_note
WHERE author_id IN (SELECT id FROM seed_person);

DELETE FROM mentorship
WHERE mentor_id IN (SELECT id FROM seed_person)
   OR organization_id IN (SELECT id FROM seed_startup);

DELETE FROM mentor_availability_slot
WHERE mentor_id IN (SELECT id FROM seed_person);

DELETE FROM mentor_profile
WHERE user_id IN (SELECT id FROM seed_person);

UPDATE organization
SET founder_id = NULL
WHERE founder_id IN (SELECT id FROM seed_person);

-- Anyone else who joined a seeded startup was testing too; they keep their account but lose the startup.
UPDATE app_user
SET organization_id = NULL
WHERE organization_id IN (SELECT id FROM seed_startup)
   OR id IN (SELECT id FROM seed_person);

DELETE FROM app_user
WHERE id IN (SELECT id FROM seed_person);

DELETE FROM organization
WHERE id IN (SELECT id FROM seed_startup);

DROP TABLE seed_meeting;
DROP TABLE seed_startup;
DROP TABLE seed_person;
