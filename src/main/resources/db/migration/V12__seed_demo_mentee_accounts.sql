ALTER TABLE app_user
    ADD COLUMN organization_id UUID REFERENCES organization(id),
    ADD COLUMN job_title VARCHAR(255);

CREATE INDEX idx_app_user_role_organization
    ON app_user(role, organization_id)
    WHERE organization_id IS NOT NULL;

UPDATE organization
SET name = 'NovaMed AI',
    description = 'Sağlık ekipleri için klinik iş akışı ve yapay zeka destekli karar araçları geliştiren girişim.'
WHERE id = '10000000-0000-0000-0000-000000000001';

UPDATE app_user
SET email = 'ayse.kaya@novamed.demo',
    full_name = 'Ayşe Kaya',
    organization_id = '10000000-0000-0000-0000-000000000001',
    job_title = 'Kurucu & CEO'
WHERE id = '00000000-0000-0000-0000-000000000003';

INSERT INTO organization (id, name, description) VALUES
    ('10000000-0000-0000-0000-000000000002', 'GreenLoop', 'Üretim şirketlerinin atık akışlarını ölçen ve döngüsel ekonomi fırsatlarını bulan ClimateTech girişimi.'),
    ('10000000-0000-0000-0000-000000000003', 'VeriKöprü', 'KOBİ''ler için güvenli veri entegrasyonu ve yapay zeka otomasyonu sunan B2B SaaS girişimi.');

INSERT INTO app_user (id, email, full_name, role, organization_id, job_title) VALUES
    ('00000000-0000-0000-0000-000000000013', 'deniz.arslan@greenloop.demo', 'Deniz Arslan', 'MENTEE', '10000000-0000-0000-0000-000000000002', 'Kurucu & Ürün Lideri'),
    ('00000000-0000-0000-0000-000000000014', 'mert.oz@verikopru.demo', 'Mert Öz', 'MENTEE', '10000000-0000-0000-0000-000000000003', 'Kurucu & CTO');

INSERT INTO mentorship (id, mentor_id, organization_id, status, start_date, goals) VALUES
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', 'ACTIVE', CURRENT_DATE - 14, 'Kurumsal pilot için etki metriklerini ve sürdürülebilir iş modelini doğrulamak.'),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', 'ACTIVE', CURRENT_DATE - 7, 'MLOps mimarisini ve ilk kurumsal müşteriye geçiş planını netleştirmek.');

INSERT INTO mentorship_request (
    id, requester_id, organization_id, mentor_id, requested_for_name,
    support_area, summary, urgency, mentorship_format, meeting_mode, status,
    requester_message, created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000012', 'Deniz Arslan', 'İş modeli ve strateji', 'Kurumsal pilotlarda karbon ve döngüsellik etkisini ölçmek için doğru metrikleri belirlemek istiyoruz.', 'NEXT_TWO_WEEKS', 'SHORT_TERM', 'ONLINE', 'ACCEPTED', 'Pilot tasarımımız ve etki metriklerimiz için görüşlerinizi almak isteriz.', NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day'),
    ('40000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000006', 'Mert Öz', 'Yazılım ve teknoloji', 'Çok kiracılı veri platformumuz için MLOps, gözlemlenebilirlik ve ölçekleme mimarisini doğrulamak istiyoruz.', 'THIS_WEEK', 'REGULAR', 'ONLINE', 'PENDING', 'Teknik mimari kararlarımızı üretim öncesinde değerlendirmek istiyoruz.', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day');

INSERT INTO meeting (
    id, organization_id, mentor_id, mentorship_request_id, scheduled_at,
    duration_minutes, agenda, meet_link, status
) VALUES
    ('50000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000012', '40000000-0000-0000-0000-000000000012', NOW() + INTERVAL '2 days', 60, 'Etki metrikleri ve kurumsal pilot hazırlığı', 'https://meet.google.com/demo-greenloop', 'SCHEDULED'),
    ('50000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000006', NULL, NOW() + INTERVAL '4 days', 45, 'MLOps mimarisi teknik değerlendirme', 'https://meet.google.com/demo-verikopru', 'SCHEDULED');

INSERT INTO profile_analysis (
    id, organization_id, source_filename, profile_type, parsed_profile,
    confirmed_profile, status, confirmed_at, created_at
) VALUES
    ('60000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000002', 'greenloop_pitch_deck.pdf', 'mentee', '{"startup_name":"GreenLoop","startup_summary":"Üretim şirketleri için atık akışı ve döngüsellik analiz platformu.","sectors":["ClimateTech","Döngüsel ekonomi"],"stage":"Pilot","team_size":5,"technologies":["IoT","Python","Karbon analitiği"]}', '{"startup_name":"GreenLoop","startup_summary":"Üretim şirketleri için atık akışı ve döngüsellik analiz platformu.","sectors":["ClimateTech","Döngüsel ekonomi"],"stage":"Pilot","team_size":5,"technologies":["IoT","Python","Karbon analitiği"]}', 'CONFIRMED', NOW() - INTERVAL '5 days', NOW() - INTERVAL '5 days'),
    ('60000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000003', 'verikopru_pitch_deck.pdf', 'mentee', '{"startup_name":"VeriKöprü","startup_summary":"KOBİ''ler için güvenli veri entegrasyonu ve yapay zeka otomasyonu.","sectors":["B2B SaaS","Yapay zeka"],"stage":"MVP","team_size":4,"technologies":["Java","Python","Kubernetes"]}', '{"startup_name":"VeriKöprü","startup_summary":"KOBİ''ler için güvenli veri entegrasyonu ve yapay zeka otomasyonu.","sectors":["B2B SaaS","Yapay zeka"],"stage":"MVP","team_size":4,"technologies":["Java","Python","Kubernetes"]}', 'CONFIRMED', NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days');
