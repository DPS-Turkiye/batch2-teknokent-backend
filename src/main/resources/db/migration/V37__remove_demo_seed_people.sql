-- Earlier migrations inserted prototype mentors, mentees and startups so the
-- UI had something to show in development (Kemal Arslan, Selin Öztürk, NovaMed,
-- GreenLoop, …). Those rows must not appear once the app is used for real.
-- Do not edit V2/V3/V9/V12/V22/V27: they already ran. This undoes their data.

CREATE TABLE seed_demo_user (id UUID PRIMARY KEY);
CREATE TABLE seed_demo_org (id UUID PRIMARY KEY);

INSERT INTO seed_demo_user (id) VALUES
    ('00000000-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-000000000002'),
    ('00000000-0000-0000-0000-000000000003'),
    ('00000000-0000-0000-0000-000000000004'),
    ('00000000-0000-0000-0000-000000000005'),
    ('00000000-0000-0000-0000-000000000006'),
    ('00000000-0000-0000-0000-000000000007'),
    ('00000000-0000-0000-0000-000000000008'),
    ('00000000-0000-0000-0000-000000000009'),
    ('00000000-0000-0000-0000-000000000010'),
    ('00000000-0000-0000-0000-000000000011'),
    ('00000000-0000-0000-0000-000000000012'),
    ('00000000-0000-0000-0000-000000000013'),
    ('00000000-0000-0000-0000-000000000014'),
    ('00000000-0000-0000-0000-000000000015'),
    ('00000000-0000-0000-0000-000000000016'),
    ('00000000-0000-0000-0000-0000000000c1');

INSERT INTO seed_demo_org (id) VALUES
    ('10000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000002'),
    ('10000000-0000-0000-0000-000000000003');

UPDATE organization
SET active_program_id = NULL
WHERE active_program_id IN (
    SELECT id FROM program WHERE created_by IN (SELECT id FROM seed_demo_user)
);

DELETE FROM program_mentor
WHERE mentor_id IN (SELECT id FROM seed_demo_user);

DELETE FROM program_startup
WHERE organization_id IN (SELECT id FROM seed_demo_org);

DELETE FROM program p
WHERE created_by IN (SELECT id FROM seed_demo_user)
  AND NOT EXISTS (
      SELECT 1 FROM program_startup ps WHERE ps.program_id = p.id
  );

DELETE FROM action_item
WHERE organization_id IN (SELECT id FROM seed_demo_org)
   OR owner_id IN (SELECT id FROM seed_demo_user)
   OR meeting_id IN (
        SELECT id FROM meeting
        WHERE organization_id IN (SELECT id FROM seed_demo_org)
           OR mentor_id IN (SELECT id FROM seed_demo_user)
   );

DELETE FROM meeting_feedback
WHERE author_id IN (SELECT id FROM seed_demo_user);

UPDATE meeting_note
SET updated_by_id = NULL
WHERE updated_by_id IN (SELECT id FROM seed_demo_user);

UPDATE meeting_todo
SET created_by_id = NULL
WHERE created_by_id IN (SELECT id FROM seed_demo_user);

UPDATE meeting_assistant_share
SET published_by_id = NULL
WHERE published_by_id IN (SELECT id FROM seed_demo_user);

UPDATE meeting
SET mentorship_request_id = NULL
WHERE mentorship_request_id IN (
    SELECT id FROM mentorship_request
    WHERE requester_id IN (SELECT id FROM seed_demo_user)
       OR mentor_id IN (SELECT id FROM seed_demo_user)
       OR organization_id IN (SELECT id FROM seed_demo_org)
);

DELETE FROM meeting
WHERE organization_id IN (SELECT id FROM seed_demo_org)
   OR mentor_id IN (SELECT id FROM seed_demo_user);

DELETE FROM mentorship_request_recommendation_item
WHERE mentor_id IN (SELECT id FROM seed_demo_user);

DELETE FROM mentorship_request
WHERE requester_id IN (SELECT id FROM seed_demo_user)
   OR mentor_id IN (SELECT id FROM seed_demo_user)
   OR organization_id IN (SELECT id FROM seed_demo_org);

DELETE FROM mentorship
WHERE mentor_id IN (SELECT id FROM seed_demo_user)
   OR organization_id IN (SELECT id FROM seed_demo_org);

DELETE FROM progress_update
WHERE organization_id IN (SELECT id FROM seed_demo_org)
   OR author_id IN (SELECT id FROM seed_demo_user);

DELETE FROM bottleneck
WHERE organization_id IN (SELECT id FROM seed_demo_org)
   OR owner_id IN (SELECT id FROM seed_demo_user);

DELETE FROM mentor_availability_slot
WHERE mentor_id IN (SELECT id FROM seed_demo_user);

DELETE FROM stored_document
WHERE user_id IN (SELECT id FROM seed_demo_user)
   OR organization_id IN (SELECT id FROM seed_demo_org);

DELETE FROM profile_analysis
WHERE organization_id IN (SELECT id FROM seed_demo_org)
   OR app_user_id IN (SELECT id FROM seed_demo_user);

DELETE FROM mentor_profile
WHERE user_id IN (SELECT id FROM seed_demo_user);

UPDATE organization
SET founder_id = NULL
WHERE founder_id IN (SELECT id FROM seed_demo_user);

UPDATE app_user
SET organization_id = NULL
WHERE id IN (SELECT id FROM seed_demo_user);

DELETE FROM app_user
WHERE id IN (SELECT id FROM seed_demo_user)
  AND id NOT IN (SELECT created_by FROM program);

DELETE FROM organization o
WHERE id IN (SELECT id FROM seed_demo_org)
  AND NOT EXISTS (
      SELECT 1 FROM app_user u WHERE u.organization_id = o.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM meeting m WHERE m.organization_id = o.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM mentorship ms WHERE ms.organization_id = o.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM mentorship_request mr WHERE mr.organization_id = o.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM program_startup ps WHERE ps.organization_id = o.id
  );

DROP TABLE seed_demo_user;
DROP TABLE seed_demo_org;
