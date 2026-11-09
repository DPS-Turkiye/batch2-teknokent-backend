package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.AppointmentKind;
import com.dps.teknokent.domain.AppointmentStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AppointmentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * The student workspace's appointments: the meetings booked on the student's peer-mentoring requests, as the mentee
 * and as the mentor. A request without a booked time is not an appointment; it stays in Mentorluk Al / Ver.
 */
@Service
@RequiredArgsConstructor
public class PeerAppointmentService {

	private final PeerMentoringService peerMentoringService;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final MeetingRepository meetingRepository;

	@Transactional(readOnly = true)
	public List<AppointmentResponse> appointments(UUID actorId) {
		AppUser student = peerMentoringService.requireStudent(actorId);
		Map<UUID, MentorshipRequest> requests = new LinkedHashMap<>();
		List<MentorshipRequest> mine = new ArrayList<>(
				mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(student.getId()));
		mine.addAll(mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(student.getId()));
		mine.stream()
				.filter(request -> PeerTopicCategory.fromText(request.getSupportArea()) != null)
				.forEach(request -> requests.putIfAbsent(request.getId(), request));
		if (requests.isEmpty()) {
			return List.of();
		}
		return meetingRepository.findByMentorshipRequestIdIn(requests.keySet()).stream()
				.map(meeting -> toResponse(meeting, requests.get(meeting.getMentorshipRequest().getId()), student))
				.sorted(Comparator.comparing(AppointmentResponse::startsAt))
				.toList();
	}

	private AppointmentResponse toResponse(Meeting meeting, MentorshipRequest request, AppUser viewer) {
		boolean asMentor = request.getMentor().getId().equals(viewer.getId());
		AppUser other = asMentor ? request.getRequester() : request.getMentor();
		return new AppointmentResponse(
				meeting.getId(),
				request.getId(),
				AppointmentKind.PEER_MENTORING,
				asMentor ? UserRole.MENTOR : UserRole.MENTEE,
				statusOf(meeting.getStatus()),
				PeerTopicCategory.fromText(request.getSupportArea()),
				request.getSummary(),
				new AppointmentResponse.Counterpart(other.getId(), other.getFullName(), null,
						peerMentoringService.departmentOf(other.getId(), null)),
				meeting.getScheduledAt(),
				meeting.getScheduledAt().plusSeconds(60L * meeting.getDurationMinutes()),
				null,
				meeting.getMeetLink(),
				null,
				null);
	}

	/** A booked slot is confirmed at once (the mentor published it); a held one stays confirmed in the history. */
	private static AppointmentStatus statusOf(MeetingStatus status) {
		return status == MeetingStatus.CANCELLED ? AppointmentStatus.CANCELLED : AppointmentStatus.CONFIRMED;
	}
}
