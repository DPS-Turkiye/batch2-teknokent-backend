package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
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

	@Test
	void recognisesEveryFinishedStatusWhateverItsCaseOrPadding() {
		for (String status : List.of("completed", "complete", "stopped", "finished", "failed", "ended", "destroyed",
				" Stopped ", "FAILED")) {
			assertThat(ProviderMeetingPresence.looksFinished(status)).as(status).isTrue();
		}
		for (String status : List.of("", "  ", "joining", "awaiting_admission", "not completed")) {
			assertThat(ProviderMeetingPresence.looksFinished(status)).as(status).isFalse();
		}
	}

	@Test
	void staysUnknownWhenThereAreNoDetailsAtAll() {
		assertThat(ProviderMeetingPresence.humanCount(null)).isEqualTo(ProviderMeetingPresence.UNKNOWN);
	}

	@Test
	void readsTheAttendeesWhenTheProviderSendsNoParticipantList() {
		ProviderBotDetails details = new ProviderBotDetails(
				"active",
				"google_meet",
				"aaa-bbbb-ccc",
				null,
				List.of(Map.of("fullName", "Sudenaz Yılmaz"), Map.of("speaker", "mentor bot"), "  Deniz Ak  "));

		assertThat(ProviderMeetingPresence.humanCount(details)).isEqualTo(2);
	}

	@Test
	void doesNotCountEntriesThatCarryNoName() {
		Map<String, Object> nameLeftEmpty = new HashMap<>();
		nameLeftEmpty.put("name", null);
		List<Object> participants = new ArrayList<>();
		participants.add(null);
		participants.add("   ");
		participants.add(Map.of("id", 7));
		participants.add(nameLeftEmpty);
		participants.add(Map.of("full_name", "Sudenaz Yılmaz"));
		ProviderBotDetails details = new ProviderBotDetails(
				"active", "google_meet", "aaa-bbbb-ccc", participants, null);

		assertThat(ProviderMeetingPresence.humanCount(details)).isEqualTo(1);
	}

	@Test
	void takesTheFirstNameFieldThatHasAValue() {
		Map<String, Object> entry = new HashMap<>();
		entry.put("name", null);
		entry.put("displayName", " Ayşe Demir ");
		entry.put("speaker", "Konuşmacı 2");

		assertThat(ProviderMeetingPresence.nameOf(entry)).isEqualTo("Ayşe Demir");
	}

	// An entry in a shape we do not know is still somebody in the room, so the bot is not pulled out on them.
	@Test
	void anEntryOfAnUnknownShapeStillCountsAsSomebodyPresent() {
		ProviderBotDetails details = new ProviderBotDetails(
				"active", "google_meet", "aaa-bbbb-ccc", List.of(42), null);

		assertThat(ProviderMeetingPresence.humanCount(details)).isEqualTo(1);
	}
}
