package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MeetingFeedbackAnswersTest {

	@Test
	void separatesAMenteesChosenBenefitsFromTheirNote() {
		var split = MeetingFeedbackAnswers.split(List.of(
				"Önceliklerimi netleştirmek", "Kaynak / bağlantı önerileri", "Bir sonraki görüşmede finansmanı konuşalım."), MeetingFeedbackSide.MENTEE);

		assertThat(split.qualities()).containsExactly("Önceliklerimi netleştirmek", "Kaynak / bağlantı önerileri");
		assertThat(split.note()).isEqualTo("Bir sonraki görüşmede finansmanı konuşalım.");
	}

	@Test
	void separatesAMentorsQualitiesFromTheirNote() {
		var split = MeetingFeedbackAnswers.split(
				List.of("Hazırlıklı", "Çözüm odaklı", "Ekip net hedeflerle geldi."), MeetingFeedbackSide.MENTOR);

		assertThat(split.qualities()).containsExactly("Hazırlıklı", "Çözüm odaklı");
		assertThat(split.note()).isEqualTo("Ekip net hedeflerle geldi.");
	}

	@Test
	void hasNoNoteWhenOnlyFixedQualitiesWereChosen() {
		var split = MeetingFeedbackAnswers.split(List.of("Saygılı", "Odaklı"), MeetingFeedbackSide.MENTOR);

		assertThat(split.qualities()).containsExactly("Saygılı", "Odaklı");
		assertThat(split.note()).isNull();
	}

	// The note is not always last: anything outside the fixed lists counts, wherever it sits.
	@Test
	void keepsEveryFreeTextEntryAsTheNoteAndSkipsBlanks() {
		var split = MeetingFeedbackAnswers.split(
				List.of("İlk satır", " ", "Yapıcı", "İkinci satır"), MeetingFeedbackSide.MENTOR);

		assertThat(split.qualities()).containsExactly("Yapıcı");
		assertThat(split.note()).isEqualTo("İlk satır\nİkinci satır");
	}

	@Test
	void aNoteThatOnlyStartsLikeAQualityStaysANote() {
		var split = MeetingFeedbackAnswers.split(List.of("Hazırlıklı değildi"), MeetingFeedbackSide.MENTOR);

		assertThat(split.qualities()).isEmpty();
		assertThat(split.note()).isEqualTo("Hazırlıklı değildi");
	}

	// Each side is read against its own form: a one-word mentee note that matches a mentor quality is still a note.
	@Test
	void readsEachSideAgainstItsOwnFormsQualities() {
		var mentee = MeetingFeedbackAnswers.split(List.of("Yeni bir bakış açısı kazanmak", "Saygılı"), MeetingFeedbackSide.MENTEE);
		var mentor = MeetingFeedbackAnswers.split(List.of("Saygılı", "Somut aksiyonlar belirlemek"), MeetingFeedbackSide.MENTOR);

		assertThat(mentee.qualities()).containsExactly("Yeni bir bakış açısı kazanmak");
		assertThat(mentee.note()).isEqualTo("Saygılı");
		assertThat(mentor.qualities()).containsExactly("Saygılı");
		assertThat(mentor.note()).isEqualTo("Somut aksiyonlar belirlemek");
	}
}
