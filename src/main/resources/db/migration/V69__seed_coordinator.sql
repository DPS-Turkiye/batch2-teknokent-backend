-- A platform coordinator next to the V60 admin, linked to its Firebase login by uid (one Firebase project for
-- local, dev and prod). The IT office changes the password or email in the Firebase console; the row stays put.
INSERT INTO app_user (id, email, full_name, role, status, firebase_uid, job_title)
VALUES (
    'a9d5c2e0-4b1f-4c8e-9f3a-5e7d2c1b0a02',
    'arya@coor.tnt',
    'Program Koordinatörü',
    'COORDINATOR',
    'APPROVED',
    '0v2rn1CZxyQwSZ2kiXc1oGLbGE12',
    'Program koordinatörü'
)
ON CONFLICT (email) DO UPDATE
SET role = 'COORDINATOR',
    status = 'APPROVED',
    firebase_uid = EXCLUDED.firebase_uid;
