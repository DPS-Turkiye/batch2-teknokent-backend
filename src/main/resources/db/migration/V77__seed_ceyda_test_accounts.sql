-- Two test accounts for Ceyda, a mentor and the founder of Flexvision, both in the V73 Growth Circuit 2026 program
-- so she can try a request from one to the other. Unlike V73 these people sign in: the Firebase logins are created by
-- hand in the Firebase console, so no password is ever in this repository, and the first sign-in links each login to
-- its row by email. The addresses are on our own domain, so nothing the app or Firebase sends reaches a stranger.

INSERT INTO app_user (id, email, full_name, role, status, job_title, bio) VALUES
    ('c3da0000-0000-4000-8000-000000000001', 'ceyda.mentor@menton.com.tr', 'Ceyda Mentor', 'MENTOR', 'APPROVED',
     'Ürün ve Strateji Mentoru',
     'Menton test hesabı. Erken aşama girişimlere ürün stratejisi, kullanıcı araştırması ve MVP planlaması konularında mentorluk verir.');

INSERT INTO mentor_profile (
    user_id, initials, professional_title, company, availability_label, response_time_label, meeting_mode,
    communication_preference, mentoring_style, fit_label, experience_level, sector_experience, startup_stages,
    sort_order, mentee_capacity, about, meeting_frequency, prep_file_requirement
) VALUES
    ('c3da0000-0000-4000-8000-000000000001', 'CM', 'Ürün ve Strateji Mentoru', 'Menton',
     'Mentorluğa açık', '~24 saat', 'Online', '', 'Uygulamalı, soru odaklı', '', '5+ yıl',
     'B2B SaaS, EdTech', 'Fikir, Prototip, MVP', 106, 3,
     'Menton test hesabı. Erken aşama girişimlere ürün stratejisi, kullanıcı araştırması ve MVP planlaması konularında mentorluk verir.',
     'BIWEEKLY', 'OPTIONAL');

INSERT INTO mentor_specialty (mentor_id, display_order, specialty) VALUES
    ('c3da0000-0000-4000-8000-000000000001', 0, 'Ürün Stratejisi'),
    ('c3da0000-0000-4000-8000-000000000001', 1, 'Kullanıcı Araştırması'),
    ('c3da0000-0000-4000-8000-000000000001', 2, 'MVP Planlama');

INSERT INTO mentor_language (mentor_id, display_order, language) VALUES
    ('c3da0000-0000-4000-8000-000000000001', 0, 'TURKISH'),
    ('c3da0000-0000-4000-8000-000000000001', 1, 'ENGLISH');

-- Flexvision's details come from its own site (flexvision.com.tr). What the site doesn't say (founding year, funding
-- stage, completion) is left empty for Ceyda to fill in.
INSERT INTO organization (
    id, name, description, sector, maturity, current_stage, problem, solution, target_user, status_summary
) VALUES
    ('c3da0000-0000-4000-8000-000000000021', 'Flexvision',
     'Kamu veya özel sektörde uzun yıllar görev almış deneyimli bağımsız profesyonelleri; liderlik, uzmanlık, mentorluk/koçluk, eğitim, konferans konuşması ve yönetim danışmanlığı arayan şirket ve kurumlarla buluşturan dijital pazaryeri.',
     'Bağımsız Profesyonel Pazaryeri (B2B)', 'MVP', 'MARKET',
     'Şirketler kıdemli uzmanlığa ihtiyaç duydukları anda ve süre boyunca esnek biçimde ulaşamıyor; kadrolu çalışmayı bırakan deneyimli profesyonellerin birikimi kullanılmadan kalıyor.',
     'Profesyonellerin profil ve ilan, şirketlerin ihtiyaç ilanı açtığı, bölüm ve filtrelerle eşleştiren bir platform. Platformda yer almak ve ilan vermek ücretsiz; şirketler kredi paketiyle profesyonelin iletişim bilgilerine erişiyor.',
     'Deneyimli bağımsız profesyoneller ve onlardan proje bazlı destek almak isteyen şirket/kurumlar',
     'Platform canlı (Türkçe ve İngilizce): 12 bölümde ilanlar, profesyonel profilleri ve şirket/kurum kaydı açık.');

INSERT INTO app_user (id, email, full_name, role, status, job_title, organization_id) VALUES
    ('c3da0000-0000-4000-8000-000000000011', 'ceyda.mentee@menton.com.tr', 'Ceyda Mentee', 'MENTEE', 'APPROVED',
     'Kurucu', 'c3da0000-0000-4000-8000-000000000021');

UPDATE organization SET founder_id = 'c3da0000-0000-4000-8000-000000000011'
WHERE id = 'c3da0000-0000-4000-8000-000000000021';

INSERT INTO organization_support_area (organization_id, display_order, support_area) VALUES
    ('c3da0000-0000-4000-8000-000000000021', 0, 'Büyüme'),
    ('c3da0000-0000-4000-8000-000000000021', 1, 'Satış');

INSERT INTO program_mentor (id, program_id, mentor_id, status, invited_by, decided_at, role) VALUES
    ('c3da0000-0000-4000-8000-000000000041', 'c1a00000-0000-4000-8000-000000000030',
     'c3da0000-0000-4000-8000-000000000001', 'ACCEPTED', 'COORDINATOR', NOW(), 'Ürün mentoru');

INSERT INTO program_startup (id, program_id, organization_id) VALUES
    ('c3da0000-0000-4000-8000-000000000051', 'c1a00000-0000-4000-8000-000000000030',
     'c3da0000-0000-4000-8000-000000000021');

UPDATE organization SET active_program_id = 'c1a00000-0000-4000-8000-000000000030'
WHERE id = 'c3da0000-0000-4000-8000-000000000021';
