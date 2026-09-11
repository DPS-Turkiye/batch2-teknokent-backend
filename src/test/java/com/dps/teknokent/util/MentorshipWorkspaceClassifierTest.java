package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipWorkspaceTab;

class MentorshipWorkspaceClassifierTest {

	private final UUID requestId = UUID.randomUUID();
	private final UUID mentorId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final Instant now = Instant.parse("2026-09-11T12:00:00Z");

	@Test
	void activeRequiresAcceptedOpenMeeting() {
		var request = acceptedRequest();
		var open = meeting(requestId, MeetingStatus.SCHEDULED, Instant.parse("2026-09-11T11:30:00Z"), 60);

		assertThat(MentorshipWorkspaceClassifier.matches(
				request, List.of(open), MentorshipWorkspaceTab.ACTIVE, now)).isTrue();
		assertThat(MentorshipWorkspaceClassifier.matches(
				request, List.of(open), MentorshipWorkspaceTab.HISTORY, now)).isFalse();
	}

	@Test
	void historyTakesEndedAcceptedMeetings() {
		var request = acceptedRequest();
		var ended = meeting(requestId, MeetingStatus.COMPLETED, Instant.parse("2026-09-10T10:00:00Z"), 45);

		assertThat(MentorshipWorkspaceClassifier.matches(
				request, List.of(ended), MentorshipWorkspaceTab.HISTORY, now)).isTrue();
		assertThat(MentorshipWorkspaceClassifier.matches(
				request, List.of(ended), MentorshipWorkspaceTab.ACTIVE, now)).isFalse();
	}

	@Test
	void requestsIncludesPendingAndAcceptedWithoutMeeting() {
		var pending = new MentorshipWorkspaceClassifier.RequestRow(
				UUID.randomUUID(),
				MentorshipRequestStatus.PENDING,
				mentorId,
				organizationId,
				now);
		var accepted = acceptedRequest();

		assertThat(MentorshipWorkspaceClassifier.matches(
				pending, List.of(), MentorshipWorkspaceTab.REQUESTS, now)).isTrue();
		assertThat(MentorshipWorkspaceClassifier.matches(
				accepted, List.of(), MentorshipWorkspaceTab.REQUESTS, now)).isTrue();
	}

	private MentorshipWorkspaceClassifier.RequestRow acceptedRequest() {
		return new MentorshipWorkspaceClassifier.RequestRow(
				requestId,
				MentorshipRequestStatus.ACCEPTED,
				mentorId,
				organizationId,
				now);
	}

	private MentorshipWorkspaceClassifier.MeetingRow meeting(
			UUID mentorshipRequestId,
			MeetingStatus status,
			Instant scheduledAt,
			int durationMinutes) {
		return new MentorshipWorkspaceClassifier.MeetingRow(
				UUID.randomUUID(),
				status,
				scheduledAt,
				durationMinutes,
				mentorshipRequestId,
				mentorId,
				organizationId);
	}
}
