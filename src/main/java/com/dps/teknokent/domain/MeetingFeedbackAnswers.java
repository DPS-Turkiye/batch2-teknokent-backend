package com.dps.teknokent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Both feedback forms store the qualities a person picks as fixed Turkish texts in {@code benefits}, then any
 * free-text note as one more entry. The texts mirror the frontend's {@code MEETING_FEEDBACK_BENEFITS} (mentee form)
 * and {@code MENTOR_EVALUATION_QUALITIES} (mentor form); they have only ever been added to, never reworded. Anything
 * that is not one of the author's form's texts is the note, the rule the mentee form uses when it reloads an answer.
 */
public final class MeetingFeedbackAnswers {

	static final Set<String> MENTEE_QUALITIES = Set.of(
			"Önceliklerimi netleştirmek",
			"Finansal modeli anlamak",
			"Varsayımlarımı sorgulamak",
			"Somut aksiyonlar belirlemek",
			"Yeni bir bakış açısı kazanmak",
			"Kaynak / bağlantı önerileri");

	static final Set<String> MENTOR_QUALITIES = Set.of(
			"Hazırlıklı",
			"Açık iletişim",
			"Net sorular sordu",
			"Geri bildirime açık",
			"Aktif katılım",
			"Odaklı",
			"Yapıcı",
			"Çözüm odaklı",
			"Motivasyonu yüksek",
			"Saygılı");

	public record Split(List<String> qualities, String note) {
	}

	private MeetingFeedbackAnswers() {
	}

	public static Split split(List<String> benefits, MeetingFeedbackSide side) {
		Set<String> fixed = side == MeetingFeedbackSide.MENTOR ? MENTOR_QUALITIES : MENTEE_QUALITIES;
		List<String> qualities = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		for (String benefit : benefits) {
			if (benefit == null || benefit.isBlank()) {
				continue;
			}
			if (fixed.contains(benefit.trim())) {
				qualities.add(benefit.trim());
			} else {
				notes.add(benefit.trim());
			}
		}
		return new Split(List.copyOf(qualities), notes.isEmpty() ? null : String.join("\n", notes));
	}
}
