package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.dto.provider.ProviderBotDetails;

class ProviderMeetingPresenceTest {

	@Test
	void countsPeopleAndSkipsTheTranscriptBot() {
		ProviderBotDetails details = new ProviderBotDetails(
				"active",
				"google_meet",
				"aaa-bbbb-ccc",
				List.of(
						Map.of("name", "Sudenaz Yılmaz"),
						Map.of("display_name", "Mentor Bot"),
						"Vexa Notetaker"),
				null);

		assertThat(ProviderMeetingPresence.humanCount(details)).isEqualTo(1);
	}

	@Test
	void treatsAnEmptyParticipantListAsNobodyLeft() {
		ProviderBotDetails details = new ProviderBotDetails(
				"active", "google_meet", "aaa-bbbb-ccc", List.of(), null);

		assertThat(ProviderMeetingPresence.humanCount(details)).isZero();
	}

	@Test
	void staysUnknownWhenTheProviderOmittedParticipants() {
		ProviderBotDetails details = new ProviderBotDetails(
				"active", "google_meet", "aaa-bbbb-ccc", null, null);

		assertThat(ProviderMeetingPresence.humanCount(details)).isEqualTo(ProviderMeetingPresence.UNKNOWN);
	}

	@Test
	void recognisesFinishedBotStatuses() {
		assertThat(ProviderMeetingPresence.looksFinished("completed")).isTrue();
		assertThat(ProviderMeetingPresence.looksFinished("ENDED")).isTrue();
		assertThat(ProviderMeetingPresence.looksFinished("active")).isFalse();
		assertThat(ProviderMeetingPresence.looksFinished(null)).isFalse();
	}
}
