UPDATE app_user
SET email = 'kemal.arslan@teknokent.demo', full_name = 'Dr. Kemal Arslan'
WHERE id = '00000000-0000-0000-0000-000000000002';

INSERT INTO app_user (id, email, full_name, role) VALUES
    ('00000000-0000-0000-0000-000000000004', 'selin.ozturk@teknokent.demo', 'Selin Öztürk', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000005', 'murat.celik@teknokent.demo', 'Murat Çelik', 'MENTOR');

CREATE TABLE mentor_profile (
    user_id UUID PRIMARY KEY REFERENCES app_user(id),
    initials VARCHAR(8) NOT NULL,
    professional_title VARCHAR(255) NOT NULL,
    company VARCHAR(255) NOT NULL,
    availability_label VARCHAR(255) NOT NULL,
    response_time_label VARCHAR(255) NOT NULL,
    meeting_mode VARCHAR(255) NOT NULL,
    communication_preference VARCHAR(255) NOT NULL,
    mentoring_style VARCHAR(255) NOT NULL,
    fit_label VARCHAR(255) NOT NULL,
    experience_level VARCHAR(255) NOT NULL,
    sector_experience VARCHAR(255) NOT NULL,
    startup_stages VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL
);

CREATE TABLE mentor_specialty (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    specialty VARCHAR(255) NOT NULL,
    PRIMARY KEY (mentor_id, display_order)
);

CREATE TABLE mentor_match_reason (
    mentor_id UUID NOT NULL REFERENCES mentor_profile(user_id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (mentor_id, display_order)
);

INSERT INTO mentor_profile (
    user_id, initials, professional_title, company, availability_label,
    response_time_label, meeting_mode, communication_preference,
    mentoring_style, fit_label, experience_level, sector_experience,
    startup_stages, sort_order
) VALUES
    ('00000000-0000-0000-0000-000000000002', 'DK', 'Ürün & Araştırma Direktörü', 'Trendyol', 'Bu hafta müsait', '~24 saat', 'Online', 'Mesajlaşma açık', 'Yapılandırılmış & Uygulamalı', 'Çok yüksek', 'Kullanıcı testi, UX', 'E-ticaret, SaaS', 'Seed & MVP', 1),
    ('00000000-0000-0000-0000-000000000004', 'SÖ', 'Kurucu', 'HealthTech Ventures', 'Bu hafta müsait', '~48 saat', 'Online / Yüz yüze', 'Mesajlaşma açık', 'Esnek & Birlikte keşfeden', 'Yüksek', 'B2B SaaS, Sağlık', 'HealthTech, SaaS', 'MVP & Büyüme', 2),
    ('00000000-0000-0000-0000-000000000005', 'MÇ', 'Ürün Müdürü', 'Peak Games', 'Gelecek hafta müsait', '~24 saat', 'Online', 'Yalnızca toplantı', 'Yönlendirici & Stratejik', 'Orta', 'Analitik, Segmentasyon', 'Oyun, Tüketici', 'Pre-seed & Seed', 3);

INSERT INTO mentor_specialty (mentor_id, display_order, specialty) VALUES
    ('00000000-0000-0000-0000-000000000002', 0, 'Kullanıcı araştırması'),
    ('00000000-0000-0000-0000-000000000002', 1, 'MVP testi'),
    ('00000000-0000-0000-0000-000000000002', 2, 'UX stratejisi'),
    ('00000000-0000-0000-0000-000000000004', 0, 'B2B SaaS'),
    ('00000000-0000-0000-0000-000000000004', 1, 'Sağlık teknolojileri'),
    ('00000000-0000-0000-0000-000000000004', 2, 'Ürün-pazar uyumu'),
    ('00000000-0000-0000-0000-000000000005', 0, 'Ürün analitik'),
    ('00000000-0000-0000-0000-000000000005', 1, 'Kullanıcı segmentasyonu'),
    ('00000000-0000-0000-0000-000000000005', 2, 'Test metodolojisi');

INSERT INTO mentor_match_reason (mentor_id, display_order, reason) VALUES
    ('00000000-0000-0000-0000-000000000002', 0, 'MVP seviyesindeki girişimlerle çalışıyor'),
    ('00000000-0000-0000-0000-000000000002', 1, 'Kullanıcı testi süreçlerinde deneyimli'),
    ('00000000-0000-0000-0000-000000000002', 2, 'Bu hafta müsait'),
    ('00000000-0000-0000-0000-000000000004', 0, 'Sağlık teknolojileri deneyimi bulunuyor'),
    ('00000000-0000-0000-0000-000000000004', 1, 'B2B SaaS konusunda uzmanlaşmış'),
    ('00000000-0000-0000-0000-000000000004', 2, 'Kullanıcı doğrulama süreçlerinde aktif'),
    ('00000000-0000-0000-0000-000000000005', 0, 'Test metodolojisi konusunda uzmanlaşmış'),
    ('00000000-0000-0000-0000-000000000005', 1, 'Büyük kullanıcı kitleleriyle çalışma deneyimi');
