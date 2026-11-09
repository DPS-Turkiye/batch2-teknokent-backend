package com.dps.teknokent.service;

import java.util.List;

import com.dps.teknokent.dto.responses.TrainingModuleResponse.Question;

/**
 * PLACEHOLDER until the real training arrives: an example video and ten example questions, each marked [ÖRNEK],
 * so a coordinator can try the flow. The video is the sample YouTube uses in its own IFrame API documentation.
 */
final class TrainingPlaceholder {

	static final String VIDEO_ID = "M7lc1UVf-VE";

	static final List<Question> QUESTIONS = List.of(
			new Question("[ÖRNEK] Akran mentorunun ilk görüşmede yapması gereken nedir?",
					List.of("Mentee'nin hedeflerini dinlemek", "Hemen ödev vermek", "Kendi başarılarını anlatmak",
							"Görüşmeyi kısa kesmek"), 0),
			new Question("[ÖRNEK] Mentee'nin paylaştığı kişisel bilgiler için doğru yaklaşım hangisidir?",
					List.of("Arkadaşlarla paylaşmak", "Gizli tutmak", "Sosyal medyada anlatmak", "Not almadan unutmak"),
					1),
			new Question("[ÖRNEK] Mentee bir konuda zorlandığında mentor ne yapmalıdır?",
					List.of("Sorunu onun yerine çözmek", "Konuyu değiştirmek",
							"Birlikte seçenekleri düşünmek", "Görüşmeyi iptal etmek"), 2),
			new Question("[ÖRNEK] Görüşmeye gelemeyecek bir mentor ne yapmalıdır?",
					List.of("Haber vermeden gelmemek", "Başka birini göndermek", "Bir sonraki haftayı beklemek",
							"Önceden haber verip yeni zaman önermek"), 3),
			new Question("[ÖRNEK] Akran mentorluğunun temel amacı nedir?",
					List.of("Deneyim paylaşarak öğrenmeye destek olmak", "Not vermek", "Ders anlatmak",
							"Disiplin kurallarını uygulamak"), 0),
			new Question("[ÖRNEK] Mentor, bilmediği bir konuda soru gelirse ne yapmalıdır?",
					List.of("Tahminle cevap vermek", "Bilmediğini söyleyip kaynağa yönlendirmek", "Soruyu geçiştirmek",
							"Mentee'yi suçlamak"), 1),
			new Question("[ÖRNEK] Görüşme sonunda hangisi faydalıdır?",
					List.of("Hiçbir şey söylememek", "Bir sonraki görüşmeyi iptal etmek",
							"Kısa bir özet ve sonraki adımları belirlemek", "Mentee'yi eleştirmek"), 2),
			new Question("[ÖRNEK] Mentee ile iletişimde hangi tutum doğrudur?",
					List.of("Yargılayıcı olmak", "Sürekli araya girmek", "Sadece kendinden bahsetmek",
							"Saygılı ve açık olmak"), 3),
			new Question("[ÖRNEK] Bir mentor kaç mentee'ye aynı anda destek vermelidir?",
					List.of("Kapasitesi kadar, programın kuralına göre", "Sınırsız", "Hiç", "Sadece bir kez"), 0),
			new Question("[ÖRNEK] Ciddi bir sorun fark eden mentor kime başvurmalıdır?",
					List.of("Kimseye", "Program koordinatörüne", "Mentee'nin ailesine", "Sosyal medyaya"), 1));

	private TrainingPlaceholder() {
	}
}
