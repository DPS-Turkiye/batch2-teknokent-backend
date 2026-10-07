-- Presentation data: the Growth Circuit 2026 program with five mentors and five startups, run by the V60 admin
-- and the V69 coordinator. The people only need to appear in lists, so none has a Firebase login; the .test
-- addresses can never receive mail, so nothing the app sends them reaches a real inbox.
-- The admin and coordinator are found by email: their ids differ between environments.

INSERT INTO app_user (id, email, full_name, role, status, job_title, bio) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 'ahmet.yilmaz@growthcircuit.test', 'Ahmet Yılmaz', 'MENTOR', 'APPROVED',
     'Baş Ürün Yöneticisi & Çevik Koç',
     'B2B SaaS ve platform ürünlerinde 10 yılı aşkın deneyime sahip Baş Ürün Yöneticisi. Erken aşama girişimlerin fikir aşamasından MVP''ye geçiş süreçlerini hızlandırma konusunda uzman.'),
    ('c1a00000-0000-4000-8000-000000000002', 'selin.kaya@growthcircuit.test', 'Selin Kaya', 'MENTOR', 'APPROVED',
     'Büyüme & Pazarlama Stratejisti',
     'Veri odaklı büyüme, dijital pazarlama ve kullanıcı edinimi (CAC) optimizasyonunda 8 yıllık tecrübeye sahip eski kurucu ve CMO.'),
    ('c1a00000-0000-4000-8000-000000000003', 'caner.tekin@growthcircuit.test', 'Caner Tekin', 'MENTOR', 'APPROVED',
     'Yatırım Direktörü & Melek Yatırımcı',
     'Risk sermayesi (VC) fonlarında edindiği tecrübeyi melek yatırımcılıkla birleştiren, teknoloji odaklı girişimlerin finansman süreçlerini yöneten uzman.'),
    ('c1a00000-0000-4000-8000-000000000004', 'zeynep.aslan@growthcircuit.test', 'Zeynep Aslan', 'MENTOR', 'APPROVED',
     'Yapay Zeka Ekip Lideri & Veri Bilimci',
     'Doğal Dil İşleme (NLP) ve tavsiye sistemleri üzerine akademik ve sektörel geçmişi olan Kıdemli Yapay Zeka Mühendisi.'),
    ('c1a00000-0000-4000-8000-000000000005', 'burak.mert@growthcircuit.test', 'Burak Mert', 'MENTOR', 'APPROVED',
     'COO & Tedarik Zinciri Uzmanı',
     'Özellikle IoT ve donanım girişimleri ile pazar yeri platformlarının karmaşık operasyonel süreçlerini yöneten deneyimli COO.');

INSERT INTO mentor_profile (
    user_id, initials, professional_title, company, availability_label, response_time_label, meeting_mode,
    communication_preference, mentoring_style, fit_label, experience_level, sector_experience, startup_stages,
    sort_order, mentee_capacity, about, meeting_frequency, prep_file_requirement
) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 'AY', 'Baş Ürün Yöneticisi & Çevik Koç', 'TechNova SaaS',
     'Mentorluğa açık', '~24 saat', 'Online', '', 'Uygulamalı, sprint odaklı', '', '10+ yıl',
     'B2B SaaS, Platform ürünleri', 'Fikir, Prototip, MVP', 101, 5,
     'B2B SaaS ve platform ürünlerinde 10 yılı aşkın deneyime sahip Baş Ürün Yöneticisi. Erken aşama girişimlerin fikir aşamasından MVP''ye geçiş süreçlerini hızlandırma konusunda uzman.',
     'BIWEEKLY', 'OPTIONAL'),
    ('c1a00000-0000-4000-8000-000000000002', 'SK', 'Büyüme & Pazarlama Stratejisti', 'Bağımsız Danışman',
     'Mentorluğa açık', '~12 saat', 'Online', '', 'Veri odaklı, deney temelli', '', '8 yıl',
     'E-ticaret, B2B, B2C', 'MVP, Gelir, Büyüme', 102, 4,
     'Veri odaklı büyüme, dijital pazarlama ve kullanıcı edinimi (CAC) optimizasyonunda 8 yıllık tecrübeye sahip eski kurucu ve CMO.',
     'WEEKLY', 'OPTIONAL'),
    ('c1a00000-0000-4000-8000-000000000003', 'CT', 'Yatırım Direktörü & Melek Yatırımcı', 'Alpha Ventures',
     'Mentorluğa açık', '~48 saat', 'Online', '', 'Finansal disiplin, yatırımcı bakışı', '', '10+ yıl',
     'FinTech, B2B SaaS, Teknoloji yatırımları', 'MVP, Gelir, Büyüme', 103, 3,
     'Risk sermayesi (VC) fonlarında edindiği tecrübeyi melek yatırımcılıkla birleştiren, teknoloji odaklı girişimlerin finansman süreçlerini yöneten uzman.',
     'MONTHLY', 'REQUIRED'),
    ('c1a00000-0000-4000-8000-000000000004', 'ZA', 'Yapay Zeka Ekip Lideri & Veri Bilimci', 'SmartMatch AI',
     'Mentorluğa açık', '~24 saat', 'Online', '', 'Teknik ve uygulamalı', '', '8 yıl',
     'Yapay zeka, E-ticaret, DeepTech', 'Prototip, MVP, Gelir', 104, 4,
     'Doğal Dil İşleme (NLP) ve tavsiye sistemleri üzerine akademik ve sektörel geçmişi olan Kıdemli Yapay Zeka Mühendisi.',
     'BIWEEKLY', 'OPTIONAL'),
    ('c1a00000-0000-4000-8000-000000000005', 'BM', 'COO & Tedarik Zinciri Uzmanı', 'HardwareTech Inc.',
     'Mentorluğa açık', '~24 saat', 'Online', '', 'Süreç ve operasyon odaklı', '', '12+ yıl',
     'IoT, Donanım, Pazar yeri', 'Prototip, MVP, Büyüme', 105, 4,
     'Özellikle IoT ve donanım girişimleri ile pazar yeri platformlarının karmaşık operasyonel süreçlerini yöneten deneyimli COO.',
     'AS_NEEDED', 'NOT_NEEDED');

INSERT INTO mentor_specialty (mentor_id, display_order, specialty) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 0, 'User Story Mapping'),
    ('c1a00000-0000-4000-8000-000000000001', 1, 'Bullseye Önceliklendirme'),
    ('c1a00000-0000-4000-8000-000000000001', 2, 'Sprint Planlama'),
    ('c1a00000-0000-4000-8000-000000000001', 3, 'Müşteri Doğrulaması'),
    ('c1a00000-0000-4000-8000-000000000002', 0, 'Performans Pazarlaması'),
    ('c1a00000-0000-4000-8000-000000000002', 1, 'A/B Testleri'),
    ('c1a00000-0000-4000-8000-000000000002', 2, 'Go-to-Market Stratejisi'),
    ('c1a00000-0000-4000-8000-000000000002', 3, 'Kohort Analizi'),
    ('c1a00000-0000-4000-8000-000000000003', 0, 'Finansal Modelleme'),
    ('c1a00000-0000-4000-8000-000000000003', 1, 'Cap Table Yönetimi'),
    ('c1a00000-0000-4000-8000-000000000003', 2, 'Pitch Deck Hazırlığı'),
    ('c1a00000-0000-4000-8000-000000000003', 3, 'B2B Fiyatlandırma'),
    ('c1a00000-0000-4000-8000-000000000004', 0, 'Tavsiye Algoritmaları'),
    ('c1a00000-0000-4000-8000-000000000004', 1, 'NLP'),
    ('c1a00000-0000-4000-8000-000000000004', 2, 'Veri Mimarlığı'),
    ('c1a00000-0000-4000-8000-000000000004', 3, 'DeepTech Ürünleştirme'),
    ('c1a00000-0000-4000-8000-000000000005', 0, 'Donanım Üretim Süreçleri'),
    ('c1a00000-0000-4000-8000-000000000005', 1, 'Lojistik ve Tedarik Zinciri'),
    ('c1a00000-0000-4000-8000-000000000005', 2, 'Pazar Yeri Operasyonları'),
    ('c1a00000-0000-4000-8000-000000000005', 3, 'Süreç Optimizasyonu');

INSERT INTO app_user_expertise (user_id, display_order, expertise) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 0, 'Miro'),
    ('c1a00000-0000-4000-8000-000000000001', 1, 'Jira'),
    ('c1a00000-0000-4000-8000-000000000001', 2, 'Figma'),
    ('c1a00000-0000-4000-8000-000000000001', 3, 'Notion'),
    ('c1a00000-0000-4000-8000-000000000002', 0, 'Google Analytics'),
    ('c1a00000-0000-4000-8000-000000000002', 1, 'Mixpanel'),
    ('c1a00000-0000-4000-8000-000000000002', 2, 'HubSpot'),
    ('c1a00000-0000-4000-8000-000000000002', 3, 'Meta Ads Manager'),
    ('c1a00000-0000-4000-8000-000000000003', 0, 'İleri Düzey Excel'),
    ('c1a00000-0000-4000-8000-000000000003', 1, 'Pitchbook'),
    ('c1a00000-0000-4000-8000-000000000003', 2, 'Carta'),
    ('c1a00000-0000-4000-8000-000000000004', 0, 'Python'),
    ('c1a00000-0000-4000-8000-000000000004', 1, 'TensorFlow'),
    ('c1a00000-0000-4000-8000-000000000004', 2, 'PyTorch'),
    ('c1a00000-0000-4000-8000-000000000004', 3, 'AWS SageMaker'),
    ('c1a00000-0000-4000-8000-000000000005', 0, 'SAP'),
    ('c1a00000-0000-4000-8000-000000000005', 1, 'Oracle SCM'),
    ('c1a00000-0000-4000-8000-000000000005', 2, 'Asana');

INSERT INTO mentor_language (mentor_id, display_order, language) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 0, 'TURKISH'),
    ('c1a00000-0000-4000-8000-000000000001', 1, 'ENGLISH'),
    ('c1a00000-0000-4000-8000-000000000002', 0, 'TURKISH'),
    ('c1a00000-0000-4000-8000-000000000002', 1, 'ENGLISH'),
    ('c1a00000-0000-4000-8000-000000000003', 0, 'TURKISH'),
    ('c1a00000-0000-4000-8000-000000000003', 1, 'ENGLISH'),
    ('c1a00000-0000-4000-8000-000000000004', 0, 'TURKISH'),
    ('c1a00000-0000-4000-8000-000000000004', 1, 'ENGLISH'),
    ('c1a00000-0000-4000-8000-000000000005', 0, 'TURKISH'),
    ('c1a00000-0000-4000-8000-000000000005', 1, 'ENGLISH');

INSERT INTO mentor_experience (mentor_id, display_order, title, organization, period, summary) VALUES
    ('c1a00000-0000-4000-8000-000000000001', 0, 'CPO (Chief Product Officer)', 'TechNova SaaS', '2021 - Günümüz',
     'Ürün vizyonunu belirleyip 20 kişilik çevik ürün ekibini yönetiyor.'),
    ('c1a00000-0000-4000-8000-000000000001', 1, 'Kıdemli Ürün Yöneticisi', 'DataFlow', '2017 - 2021',
     'Sprint planlama süreçlerini optimize etti, pazar uyumu (PMF) yakalayan 3 yeni modül canlıya aldı.'),
    ('c1a00000-0000-4000-8000-000000000001', 2, 'Kuluçka Merkezi Mentoru', 'Teknopark Kuluçka Merkezi', '2019 - Günümüz',
     'Ön hızlandırma programlarında 40+ öğrenci ve girişimci ekibe ürün yönetimi mentorluğu yaptı.'),
    ('c1a00000-0000-4000-8000-000000000002', 0, 'Kurucu Ortak & CMO', 'ShopZ', '2020 - 2024',
     'E-ticaret altyapı girişimini sıfırdan 50.000 aktif kullanıcıya taşıyıp başarılı bir exit süreci yönetti.'),
    ('c1a00000-0000-4000-8000-000000000002', 1, 'Growth Lead', 'MarketHub', '2016 - 2020',
     'B2B ve B2C dönüşüm hunilerini optimize ederek müşteri edinme maliyetlerini %40 düşürdü.'),
    ('c1a00000-0000-4000-8000-000000000003', 0, 'Yatırım Direktörü', 'Alpha Ventures', '2022 - Günümüz',
     'Tohum ve Seri A aşamasındaki teknoloji girişimlerinin durum tespiti ve değerleme süreçlerini yönetiyor.'),
    ('c1a00000-0000-4000-8000-000000000003', 1, 'Finansal Analist', 'Global Investment Partners', '2015 - 2022',
     'Portföy şirketlerinin gelir modellerini yapılandırdı.'),
    ('c1a00000-0000-4000-8000-000000000004', 0, 'Yapay Zeka Ekip Lideri', 'SmartMatch AI', '2021 - Günümüz',
     'E-ticaret platformları için kullanıcı davranışlarını analiz eden eşleştirme motorları tasarladı.'),
    ('c1a00000-0000-4000-8000-000000000004', 1, 'Veri Bilimcisi', 'TechLabs', '2018 - 2021',
     'Makine öğrenmesi modellerinin doğruluğunu artırarak platform içi etkileşimi %35 yükseltti.'),
    ('c1a00000-0000-4000-8000-000000000005', 0, 'COO', 'HardwareTech Inc.', '2019 - Günümüz',
     'Asya merkezli tedarik zincirini kurarak üretim maliyetlerini %20 optimize etti, uluslararası lojistik ağını yönetti.'),
    ('c1a00000-0000-4000-8000-000000000005', 1, 'Operasyon Müdürü', 'FastDelivery', '2014 - 2019',
     'Saha operasyonlarını ve depo süreçlerini ölçeklendirdi.');

INSERT INTO organization (
    id, name, description, sector, maturity, current_stage, funding_stage, founded_year, problem, solution,
    target_user, status_summary, completion_percent
) VALUES
    ('c1a00000-0000-4000-8000-000000000021', 'EduSync',
     'Üniversite kampüslerindeki parçalı yapıyı tek bir platformda toplayan; etkinlik takibi, öğrenci kulübü yönetimi ve dijital öğrenci kartı işlevlerini birleştiren uygulama.',
     'EdTech / Kampüs Sistemleri', 'Prototip', 'SOLUTION', 'Pre-seed', 2025,
     'Kampüs içi etkinlik, kulüp ve kart işlemleri birbirinden kopuk araçlara dağılmış durumda.',
     'Etkinlik takibi, kulüp yönetimi ve dijital öğrenci kartını tek mobil uygulamada birleştirmek.',
     'Üniversite öğrencileri ve öğrenci kulüpleri',
     'Öğrenci anketleriyle ihtiyaç doğrulandı (500+ katılım). Mobil uygulamanın arayüz tasarımı tamamlandı, yazılım geliştirmesi sürüyor.',
     35),
    ('c1a00000-0000-4000-8000-000000000022', 'AgroSmart',
     'Toprak altındaki IoT sensörleriyle nem, mineral ve pH değerlerini ölçen; verileri yapay zeka ile işleyerek çiftçilere sulama ve gübreleme reçeteleri sunan teknoloji.',
     'IoT / Tarım Teknolojileri (DeepTech)', 'MVP', 'MVP', 'Pre-seed', 2024,
     'Çiftçiler sulama ve gübrelemeyi ölçüme değil tahmine dayanarak yapıyor.',
     'Sensör verisini bulutta işleyip mobil uygulamada sulama ve gübreleme reçetesine çevirmek.',
     'Çiftçiler ve tarım kooperatifleri',
     'İlk sensör prototipi üretildi, 3 tarlada pilot test başladı. Veri akışı sağlanıyor; makine öğrenmesi modeli henüz tam optimize değil.',
     50),
    ('c1a00000-0000-4000-8000-000000000023', 'FinFlow',
     'KOBİ''lerin banka hesaplarını açık bankacılık API''leriyle tek ekrana bağlayan, 3 aylık nakit akışını tahmin eden ve e-fatura entegrasyonu sunan finansal yönetim aracı.',
     'FinTech / B2B SaaS', 'Gelir', 'FINANCE', 'Pre-seed', 2023,
     'KOBİ''ler birden fazla bankadaki nakit durumlarını tek bakışta göremiyor ve nakit akışını öngöremiyor.',
     'Açık bankacılıkla hesapları birleştirip geçmiş veriden 3 aylık nakit akışı tahmini üretmek.',
     'KOBİ''ler ve finans ekipleri',
     'Ürün canlıda, 40+ aktif KOBİ müşterisi düzenli ödeme yapıyor (MRR mevcut). Ölçeklenmek için tohum yatırımı aranıyor.',
     75),
    ('c1a00000-0000-4000-8000-000000000024', 'StyleAI',
     'Kullanıcıların fotoğraflarını ve beğendiği tarzları analiz ederek yerel butiklerin envanterindeki uygun ürünleri eşleştiren yapay zeka tabanlı moda pazar yeri.',
     'Yapay Zeka Pazar Yeri / E-Ticaret', 'MVP', 'MARKET', 'Pre-seed', 2024,
     'Niş tarzları seven kullanıcılar yerel butiklerdeki uygun ürünleri bulamıyor.',
     'Görüntü işlemeyle kullanıcı tarzını çıkarıp yerel butik envanteriyle eşleştirmek.',
     'Moda tüketicileri ve yerel butikler',
     'Görüntü işleme algoritması %80 doğrulukla çalışıyor. 15 yerel butik satıcı olarak katıldı; B2C tarafında büyüme henüz yavaş.',
     55),
    ('c1a00000-0000-4000-8000-000000000025', 'MentorMatch',
     'Teknoloji parklarındaki şirket çalışanları ile üniversite öğrencilerini ilgi alanları, yetkinlikleri ve çalışma kültürlerine göre yapay zeka destekli eşleştiren mentor-mentee platformu.',
     'İnsan Kaynakları / Kariyer Ağı (B2B2C)', 'Fikir', 'SOLUTION', 'Kendi kaynaklarıyla', 2026,
     'Teknoparklardaki mentorluk eşleşmeleri elle ve tutarsız yapılıyor.',
     'Yetkinlik ve çalışma kültürü verisiyle mentor ve mentee''yi yapay zeka ile eşleştirmek.',
     'Teknopark şirketleri, program yöneticileri ve üniversite öğrencileri',
     'Hedef kitlenin problemleri doğrulandı, User Story Mapping yapıldı ve Bullseye ile MVP kapsamı belirlendi. Yazılım süreci yeni başladı.',
     20);

INSERT INTO app_user (id, email, full_name, role, status, job_title, organization_id) VALUES
    ('c1a00000-0000-4000-8000-000000000011', 'elif.demir@growthcircuit.test', 'Elif Demir', 'MENTEE', 'APPROVED',
     'Kurucu & CEO', 'c1a00000-0000-4000-8000-000000000021'),
    ('c1a00000-0000-4000-8000-000000000012', 'murat.kara@growthcircuit.test', 'Murat Kara', 'MENTEE', 'APPROVED',
     'Kurucu & Donanım Mühendisi', 'c1a00000-0000-4000-8000-000000000022'),
    ('c1a00000-0000-4000-8000-000000000013', 'emre.aydin@growthcircuit.test', 'Emre Aydın', 'MENTEE', 'APPROVED',
     'Kurucu & CEO', 'c1a00000-0000-4000-8000-000000000023'),
    ('c1a00000-0000-4000-8000-000000000014', 'defne.yildiz@growthcircuit.test', 'Defne Yıldız', 'MENTEE', 'APPROVED',
     'Kurucu & CEO', 'c1a00000-0000-4000-8000-000000000024'),
    ('c1a00000-0000-4000-8000-000000000015', 'can.ozturk@growthcircuit.test', 'Can Öztürk', 'MENTEE', 'APPROVED',
     'Kurucu & CEO', 'c1a00000-0000-4000-8000-000000000025');

UPDATE organization SET founder_id = 'c1a00000-0000-4000-8000-000000000011' WHERE id = 'c1a00000-0000-4000-8000-000000000021';
UPDATE organization SET founder_id = 'c1a00000-0000-4000-8000-000000000012' WHERE id = 'c1a00000-0000-4000-8000-000000000022';
UPDATE organization SET founder_id = 'c1a00000-0000-4000-8000-000000000013' WHERE id = 'c1a00000-0000-4000-8000-000000000023';
UPDATE organization SET founder_id = 'c1a00000-0000-4000-8000-000000000014' WHERE id = 'c1a00000-0000-4000-8000-000000000024';
UPDATE organization SET founder_id = 'c1a00000-0000-4000-8000-000000000015' WHERE id = 'c1a00000-0000-4000-8000-000000000025';

INSERT INTO organization_support_area (organization_id, display_order, support_area) VALUES
    ('c1a00000-0000-4000-8000-000000000021', 0, 'Ürün & UX'),
    ('c1a00000-0000-4000-8000-000000000021', 1, 'User Research'),
    ('c1a00000-0000-4000-8000-000000000022', 0, 'Teknoloji'),
    ('c1a00000-0000-4000-8000-000000000022', 1, 'Satış'),
    ('c1a00000-0000-4000-8000-000000000023', 0, 'Yatırım'),
    ('c1a00000-0000-4000-8000-000000000023', 1, 'Finans'),
    ('c1a00000-0000-4000-8000-000000000024', 0, 'Büyüme'),
    ('c1a00000-0000-4000-8000-000000000024', 1, 'Pazarlama'),
    ('c1a00000-0000-4000-8000-000000000025', 0, 'Value Proposition'),
    ('c1a00000-0000-4000-8000-000000000025', 1, 'Ürün & UX');

-- Created by the V60 admin; any approved admin if that address was ever changed, so the migration cannot fail.
INSERT INTO program (id, name, description, status, start_date, end_date, created_by)
VALUES (
    'c1a00000-0000-4000-8000-000000000030',
    'Growth Circuit 2026',
    'Erken aşama teknoloji girişimlerini ürün, büyüme, finansman, yapay zeka ve operasyon mentorlarıyla buluşturan 6 aylık hızlandırma programı.',
    'ACTIVE',
    DATE '2026-10-01',
    DATE '2027-03-31',
    COALESCE(
        (SELECT id FROM app_user WHERE email = 'arya@admin.tnt'),
        (SELECT id FROM app_user WHERE role = 'ADMIN' AND status = 'APPROVED' ORDER BY created_at LIMIT 1))
);

INSERT INTO program_coordinator (id, program_id, user_id, added_by)
SELECT 'c1a00000-0000-4000-8000-000000000031', 'c1a00000-0000-4000-8000-000000000030', coordinator.id, program.created_by
FROM app_user coordinator
JOIN program ON program.id = 'c1a00000-0000-4000-8000-000000000030'
WHERE coordinator.email = 'arya@coor.tnt';

INSERT INTO program_mentor (id, program_id, mentor_id, status, invited_by, decided_at, role) VALUES
    ('c1a00000-0000-4000-8000-000000000041', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000001', 'ACCEPTED', 'COORDINATOR', NOW(), 'Ürün mentoru'),
    ('c1a00000-0000-4000-8000-000000000042', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000002', 'ACCEPTED', 'COORDINATOR', NOW(), 'Büyüme mentoru'),
    ('c1a00000-0000-4000-8000-000000000043', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000003', 'ACCEPTED', 'COORDINATOR', NOW(), 'Finans mentoru'),
    ('c1a00000-0000-4000-8000-000000000044', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000004', 'ACCEPTED', 'COORDINATOR', NOW(), 'Yapay zeka mentoru'),
    ('c1a00000-0000-4000-8000-000000000045', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000005', 'ACCEPTED', 'COORDINATOR', NOW(), 'Operasyon mentoru');

INSERT INTO program_startup (id, program_id, organization_id) VALUES
    ('c1a00000-0000-4000-8000-000000000051', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000021'),
    ('c1a00000-0000-4000-8000-000000000052', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000022'),
    ('c1a00000-0000-4000-8000-000000000053', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000023'),
    ('c1a00000-0000-4000-8000-000000000054', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000024'),
    ('c1a00000-0000-4000-8000-000000000055', 'c1a00000-0000-4000-8000-000000000030', 'c1a00000-0000-4000-8000-000000000025');

UPDATE organization
SET active_program_id = 'c1a00000-0000-4000-8000-000000000030'
WHERE id IN (
    'c1a00000-0000-4000-8000-000000000021', 'c1a00000-0000-4000-8000-000000000022',
    'c1a00000-0000-4000-8000-000000000023', 'c1a00000-0000-4000-8000-000000000024',
    'c1a00000-0000-4000-8000-000000000025');
