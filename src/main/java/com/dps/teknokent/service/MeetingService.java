package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<MeetingResponse> findAll(UUID organizationId) {
		List<Meeting> meetings = organizationId == null
				? meetingRepository.findAllByOrderByScheduledAtDesc()
				: meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(organizationId);
		return meetings.stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional
	public MeetingResponse create(CreateMeetingRequest request) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(domainLookupService.requireOrganization(request.organizationId()));
		if (request.mentorId() != null) {
			AppUser mentor = domainLookupService.requireUser(request.mentorId());
			if (mentor.getRole() != UserRole.MENTOR) {
				throw new InvalidRequestException("Selected user does not have the MENTOR role");
			}
			meeting.setMentor(mentor);
		}
		meeting.setScheduledAt(request.scheduledAt());
		meeting.setDurationMinutes(request.durationMinutes());
		meeting.setAgenda(request.agenda());
		meeting.setNotes(request.notes());
		meeting.setOutcomes(request.outcomes());
		meeting.setMeetLink(request.meetLink());
		meeting.setStatus(request.status());
		return toResponse(meetingRepository.save(meeting));
	}

	private MeetingResponse toResponse(Meeting meeting) {
		return new MeetingResponse(
				meeting.getId(),
				meeting.getMentorshipRequest() == null ? null : meeting.getMentorshipRequest().getId(),
				meeting.getOrganization().getId(),
				meeting.getOrganization().getName(),
				meeting.getMentor() == null ? null : meeting.getMentor().getId(),
				meeting.getMentor() == null ? null : meeting.getMentor().getFullName(),
				meeting.getScheduledAt(),
				meeting.getDurationMinutes(),
				meeting.getAgenda(),
				meeting.getNotes(),
				meeting.getOutcomes(),
				meeting.getMeetLink(),
				meeting.getStatus());
	}
}
