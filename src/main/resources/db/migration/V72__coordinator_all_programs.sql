-- The V69 coordinator's address receives no mail, so the email step of "Koordinatör ata" can never finish for it.
-- It is linked straight to every program that exists now; later programs are assigned from /admin/programs as usual.
-- Skips a program it created itself (program.created_by already covers it) and any link that already exists.
INSERT INTO program_coordinator (id, program_id, user_id, added_by)
SELECT gen_random_uuid(), program.id, coordinator.id, coordinator.id
FROM program
JOIN app_user coordinator ON coordinator.email = 'arya@coor.tnt'
WHERE program.created_by <> coordinator.id
ON CONFLICT (program_id, user_id) DO NOTHING;
