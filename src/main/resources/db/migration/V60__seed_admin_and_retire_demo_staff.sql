-- The platform admin, linked to its Firebase login by uid. Local, dev and prod share one Firebase project,
-- so the uid is the same everywhere. The IT office changes the password or email in the Firebase console;
-- the uid, and so this row, stays put. Admins pick coordinators from /admin from here on.
INSERT INTO app_user (id, email, full_name, role, status, firebase_uid, job_title)
VALUES (
    'a9d5c2e0-4b1f-4c8e-9f3a-5e7d2c1b0a01',
    'arya@admin.tnt',
    'Platform Yöneticisi',
    'ADMIN',
    'APPROVED',
    '4TZIqHPsgiM2FmFgVdg5xrFtEzi1',
    'Platform yöneticisi'
)
ON CONFLICT (email) DO UPDATE
SET role = 'ADMIN',
    status = 'APPROVED',
    firebase_uid = EXCLUDED.firebase_uid;

-- The demo staff from V2 and V27 have no Firebase login and could be claimed by anyone signing up with their
-- address. Rejected rather than deleted: programs and other rows still point at them.
UPDATE app_user
SET status = 'REJECTED'
WHERE email IN ('admin@teknokent.demo', 'coordinator@teknokent.demo');
