-- Mentor profiles are created at registration as of V27's accompanying change,
-- but every mentor who registered before it has no row. For them
-- PUT /api/mentors/{id} returns 404 and the profile screen cannot load, so the
-- gap is closed once here rather than left to accumulate.
INSERT INTO mentor_profile (
    user_id,
    initials,
    professional_title,
    company,
    availability_label,
    response_time_label,
    meeting_mode,
    communication_preference,
    mentoring_style,
    fit_label,
    experience_level,
    sector_experience,
    startup_stages,
    sort_order
)
SELECT
    u.id,
    -- First letter of the first two words, mirroring TextUtils.initials.
    COALESCE(
        NULLIF(
            UPPER(
                LEFT(SPLIT_PART(TRIM(u.full_name), ' ', 1), 1)
                || LEFT(SPLIT_PART(TRIM(u.full_name), ' ', 2), 1)
            ),
            ''
        ),
        '?'
    ),
    COALESCE(NULLIF(TRIM(u.job_title), ''), ''),
    '', '', '', '', '', '', '', '', '', '',
    (SELECT COALESCE(MAX(sort_order), 0) FROM mentor_profile)
        + ROW_NUMBER() OVER (ORDER BY u.created_at, u.id)
FROM app_user u
WHERE u.role = 'MENTOR'
  AND NOT EXISTS (SELECT 1 FROM mentor_profile p WHERE p.user_id = u.id);
