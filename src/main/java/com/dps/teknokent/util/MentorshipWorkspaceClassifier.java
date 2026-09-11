package com.dps.teknokent.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipWorkspaceTab;

public final class MentorshipWorkspaceClassifier {

	private static final int DEFAULT_DURATION_MINUTES = 60;
	private static final Set<MentorshipRequestStatus> REQUEST_STATUSES = EnumSet.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED);
	private static final Set<MentorshipRequestStatus> CLOSED_STATUSES = EnumSet.of(
			MentorshipRequestStatus.DECLINED,
			MentorshipRequestStatus.WITHDRAWN);

	public record RequestRow(
			UUID id,
			MentorshipRequestStatus status,
			UUID mentorId,
			UUID organizationId,
			Instant createdAt) {
	}

	public record MeetingRow(
			UUID id,
			MeetingStatus status,
			Instant scheduledAt,
			Integer durationMinutes,
			UUID mentorshipRequestId,
			UUID mentorId,
			UUID organizationId) {
	}

	private MentorshipWorkspaceClassifier() {
	}

	public static List<RequestRow> filter(
			List<RequestRow> requests,
			List<MeetingRow> meetings,
			MentorshipWorkspaceTab tab,
			Instant now) {
		return requests.stream()
				.filter(request -> matches(request, meetings, tab, now))
				.collect(Collectors.toCollection(ArrayList::new));
	}

	public static boolean matches(
			RequestRow request,
			List<MeetingRow> meetings,
			MentorshipWorkspaceTab tab,
			Instant now) {
		return switch (tab) {
			case ACTIVE -> isActive(request, meetings, now);
			case REQUESTS -> isRequest(request, meetings);
			case HISTORY -> isHistory(request, meetings, now);
		};
	}

	private static boolean isActive(RequestRow request, List<MeetingRow> meetings, Instant now) {
		return request.status() == MentorshipRequestStatus.ACCEPTED
				&& hasConfirmedMeeting(meetings, request)
				&& hasOpenMeeting(meetings, request, now);
	}

	private static boolean isRequest(RequestRow request, List<MeetingRow> meetings) {
		if (REQUEST_STATUSES.contains(request.status())) {
			return true;
		}
		return request.status() == MentorshipRequestStatus.ACCEPTED
				&& !hasConfirmedMeeting(meetings, request);
	}

	private static boolean isHistory(RequestRow request, List<MeetingRow> meetings, Instant now) {
		if (CLOSED_STATUSES.contains(request.status())) {
			return true;
		}
		return request.status() == MentorshipRequestStatus.ACCEPTED
				&& hasConfirmedMeeting(meetings, request)
				&& hasEndedMeeting(meetings, request, now)
				&& !hasOpenMeeting(meetings, request, now);
	}

	static boolean hasConfirmedMeeting(List<MeetingRow> meetings, RequestRow request) {
		return !meetingsForRequest(meetings, request).isEmpty();
	}

	static boolean hasOpenMeeting(List<MeetingRow> meetings, RequestRow request, Instant now) {
		return meetingsForRequest(meetings, request).stream()
				.anyMatch(meeting -> !isMeetingEnded(meeting, now));
	}

	static boolean hasEndedMeeting(List<MeetingRow> meetings, RequestRow request, Instant now) {
		return meetingsForRequest(meetings, request).stream()
				.anyMatch(meeting -> isMeetingEnded(meeting, now));
	}

	static List<MeetingRow> meetingsForRequest(List<MeetingRow> meetings, RequestRow request) {
		List<MeetingRow> linked = meetings.stream()
				.filter(meeting -> Objects.equals(meeting.mentorshipRequestId(), request.id()))
				.toList();
		if (!linked.isEmpty()) {
			return linked;
		}
		return meetings.stream()
				.filter(meeting -> meeting.mentorshipRequestId() == null)
				.filter(meeting -> Objects.equals(meeting.mentorId(), request.mentorId()))
				.filter(meeting -> Objects.equals(meeting.organizationId(), request.organizationId()))
				.toList();
	}

	static boolean isMeetingEnded(MeetingRow meeting, Instant now) {
		if (meeting.status() == MeetingStatus.COMPLETED) {
			return true;
		}
		Instant start = meeting.scheduledAt();
		if (start == null) {
			return false;
		}
		int duration = meeting.durationMinutes() == null
				? DEFAULT_DURATION_MINUTES
				: Math.max(1, meeting.durationMinutes());
		return now.isAfter(start.plus(duration, ChronoUnit.MINUTES));
	}
}
