-- Fictional composite profiles inspired by common roles in the Turkish startup ecosystem.
-- Names, companies, and email addresses are demo data and do not represent real affiliations.

INSERT INTO app_user (id, email, full_name, role) VALUES
    ('00000000-0000-0000-0000-000000000006', 'ece.demir@teknokent.demo', 'Ece Demir', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000007', 'burak.yalcin@teknokent.demo', 'Burak Yalçın', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000008', 'nihan.aydin@teknokent.demo', 'Nihan Aydın', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000009', 'onur.ergin@teknokent.demo', 'Onur Ergin', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000010', 'aslihan.koc@teknokent.demo', 'Aslıhan Koç', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000011', 'cemal.korkmaz@teknokent.demo', 'Cemal Korkmaz', 'MENTOR'),
    ('00000000-0000-0000-0000-000000000012', 'leyla.aksoy@teknokent.demo', 'Leyla Aksoy', 'MENTOR');

INSERT INTO mentor_profile (
    user_id, initials, professional_title, company, availability_label,
    response_time_label, meeting_mode, communication_preference,
    mentoring_style, fit_label, experience_level, sector_experience,
    startup_stages, sort_order
) VALUES
    ('00000000-0000-0000-0000-000000000006', 'ED', 'Yapay Zeka & Veri Platformları Lideri', 'Bosphorus AI Studio', 'Bu hafta müsait', '~24 saat', 'Online', 'Mesajlaşma açık', 'Teknik & Uygulamalı', 'Yüksek', '12+ yıl yazılım ve veri', 'Yapay zeka, FinTech, B2B SaaS', 'MVP & Büyüme', 4),
    ('00000000-0000-0000-0000-000000000007', 'BY', 'Büyüme ve Gelir Danışmanı', 'ScaleBridge', 'Gelecek hafta müsait', '~48 saat', 'Online / Yüz yüze', 'Toplantı sonrası mesajlaşma', 'Veri odaklı & Deneysel', 'Yüksek', '10+ yıl büyüme', 'SaaS, E-ticaret, Mobil', 'Seed & Büyüme', 5),
    ('00000000-0000-0000-0000-000000000008', 'NA', 'Yatırım Direktörü', 'Köprü Ventures', 'İki hafta içinde müsait', '~72 saat', 'Online', 'Yalnızca toplantı', 'Stratejik & Sorgulayıcı', 'Yüksek', 'Erken aşama yatırım', 'FinTech, HealthTech, Enterprise SaaS', 'Pre-seed & Series A', 6),
    ('00000000-0000-0000-0000-000000000009', 'OE', 'Fikri Mülkiyet ve Teknoloji Hukuku Danışmanı', 'FikriLab', 'Bu hafta müsait', '~24 saat', 'Online / Yüz yüze', 'Mesajlaşma açık', 'Risk odaklı & Açıklayıcı', 'Yüksek', '11+ yıl teknoloji hukuku', 'Yazılım, DeepTech, MedTech', 'Fikir & MVP', 7),
    ('00000000-0000-0000-0000-000000000010', 'AK', 'Ar-Ge Destek Programları Danışmanı', 'ArGe Destek', 'Bu hafta müsait', '~24 saat', 'Online', 'Mesajlaşma açık', 'Kontrol listeli & Sonuç odaklı', 'Yüksek', 'TÜBİTAK ve AB programları', 'DeepTech, Üniversite spin-off, SaaS', 'Fikir & Seed', 8),
    ('00000000-0000-0000-0000-000000000011', 'CK', 'Endüstriyel Ürün ve Operasyon Direktörü', 'FactoryNext', 'Gelecek hafta müsait', '~48 saat', 'Yüz yüze / Online', 'Toplantı sonrası mesajlaşma', 'Saha odaklı & Sistematik', 'Yüksek', '15+ yıl sanayi teknolojileri', 'Endüstriyel IoT, Üretim, Donanım', 'Pilot & Büyüme', 9),
    ('00000000-0000-0000-0000-000000000012', 'LA', 'ClimateTech Kurucusu ve Sürdürülebilirlik Danışmanı', 'Döngü Labs', 'İki hafta içinde müsait', '~48 saat', 'Online', 'Mesajlaşma açık', 'İşbirlikçi & Etki odaklı', 'Yüksek', 'İklim teknolojileri ve etki', 'ClimateTech, Enerji, Döngüsel ekonomi', 'MVP & Büyüme', 10);

INSERT INTO mentor_specialty (mentor_id, display_order, specialty) VALUES
    ('00000000-0000-0000-0000-000000000006', 0, 'Yapay zeka ürünleri'),
    ('00000000-0000-0000-0000-000000000006', 1, 'Veri mimarisi'),
    ('00000000-0000-0000-0000-000000000006', 2, 'MLOps ve ölçekleme'),
    ('00000000-0000-0000-0000-000000000007', 0, 'Go-to-market'),
    ('00000000-0000-0000-0000-000000000007', 1, 'B2B satış'),
    ('00000000-0000-0000-0000-000000000007', 2, 'Büyüme deneyleri'),
    ('00000000-0000-0000-0000-000000000008', 0, 'Yatırım hazırlığı'),
    ('00000000-0000-0000-0000-000000000008', 1, 'Finansal modelleme'),
    ('00000000-0000-0000-0000-000000000008', 2, 'Yatırımcı sunumu'),
    ('00000000-0000-0000-0000-000000000009', 0, 'Fikri mülkiyet'),
    ('00000000-0000-0000-0000-000000000009', 1, 'KVKK ve veri sözleşmeleri'),
    ('00000000-0000-0000-0000-000000000009', 2, 'Teknoloji lisanslama'),
    ('00000000-0000-0000-0000-000000000010', 0, 'TÜBİTAK destekleri'),
    ('00000000-0000-0000-0000-000000000010', 1, 'Avrupa Birliği fonları'),
    ('00000000-0000-0000-0000-000000000010', 2, 'Proje bütçelendirme'),
    ('00000000-0000-0000-0000-000000000011', 0, 'Endüstriyel IoT'),
    ('00000000-0000-0000-0000-000000000011', 1, 'Pilot üretim'),
    ('00000000-0000-0000-0000-000000000011', 2, 'Tedarik zinciri'),
    ('00000000-0000-0000-0000-000000000012', 0, 'Sürdürülebilir iş modeli'),
    ('00000000-0000-0000-0000-000000000012', 1, 'Karbon ölçümü'),
    ('00000000-0000-0000-0000-000000000012', 2, 'Etki yatırımı');

INSERT INTO mentor_match_reason (mentor_id, display_order, reason) VALUES
    ('00000000-0000-0000-0000-000000000006', 0, 'Yapay zeka tabanlı ürünleri üretime alma deneyimi'),
    ('00000000-0000-0000-0000-000000000006', 1, 'MVP sonrası teknik ölçekleme konusunda uzman'),
    ('00000000-0000-0000-0000-000000000007', 0, 'B2B satış hunisi ve pazara giriş deneyimi'),
    ('00000000-0000-0000-0000-000000000007', 1, 'Ölçülebilir büyüme deneyleri tasarlıyor'),
    ('00000000-0000-0000-0000-000000000008', 0, 'Erken aşama yatırım değerlendirmesinde deneyimli'),
    ('00000000-0000-0000-0000-000000000008', 1, 'Finansal model ve yatırımcı sunumu hazırlığına destek oluyor'),
    ('00000000-0000-0000-0000-000000000009', 0, 'Yazılım ve DeepTech fikri mülkiyet süreçlerinde deneyimli'),
    ('00000000-0000-0000-0000-000000000009', 1, 'Veri işleme ve teknoloji sözleşmelerini birlikte ele alıyor'),
    ('00000000-0000-0000-0000-000000000010', 0, 'TÜBİTAK ve Avrupa Birliği başvurularında deneyimli'),
    ('00000000-0000-0000-0000-000000000010', 1, 'Teknik iş paketlerini bütçe ve takvimle eşleştiriyor'),
    ('00000000-0000-0000-0000-000000000011', 0, 'Donanım pilotlarını üretim ortamına taşıma deneyimi'),
    ('00000000-0000-0000-0000-000000000011', 1, 'Sanayi müşterileriyle saha doğrulaması yürütüyor'),
    ('00000000-0000-0000-0000-000000000012', 0, 'İklim etkisini iş modeliyle birlikte değerlendiriyor'),
    ('00000000-0000-0000-0000-000000000012', 1, 'Etki yatırımı ve sürdürülebilirlik metriklerinde deneyimli');
