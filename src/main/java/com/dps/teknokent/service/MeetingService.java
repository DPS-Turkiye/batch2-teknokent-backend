package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<MeetingResponse> findAll(UUID organizationId, UUID mentorId) {
		List<Meeting> meetings;
		if (mentorId != null) {
			meetings = meetingRepository.findByMentorIdOrderByScheduledAtDesc(mentorId);
		} else if (organizationId != null) {
			meetings = meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(organizationId);
		} else {
			meetings = meetingRepository.findAllByOrderByScheduledAtDesc();
		}
		return meetings.stream()
				.map(MeetingResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MeetingResponse create(CreateMeetingRequest request) {
		AppUser mentor = null;
		if (request.mentorId() != null) {
			mentor = domainLookupService.requireUser(request.mentorId());
			if (mentor.getRole() != UserRole.MENTOR) {
				throw new InvalidRequestException("Selected user does not have the MENTOR role");
			}
		}
		Meeting meeting = Meeting.fromRequest(request,
				domainLookupService.requireOrganization(request.organizationId()), mentor);
		return MeetingResponse.fromEntity(meetingRepository.save(meeting));
	}

	@Transactional(readOnly = true)
	public List<MeetingResponse> findTimelineForRequest(UUID mentorshipRequestId) {
		MentorshipRequest request = requireMentorshipRequest(mentorshipRequestId);
		return meetingRepository
				.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(
						request.getMentor().getId(), request.getOrganization().getId())
				.stream()
				.map(MeetingResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MeetingResponse logMeeting(UUID mentorshipRequestId, LogMeetingRequest logRequest) {
		MentorshipRequest request = requireMentorshipRequest(mentorshipRequestId);
		if (request.getStatus() != MentorshipRequestStatus.ACCEPTED) {
			throw new InvalidRequestException("Meetings can only be logged for accepted requests");
		}
		if (!request.getMentor().getId().equals(logRequest.mentorId())) {
			throw new InvalidRequestException("Only the assigned mentor can log a meeting for this request");
		}
		Meeting meeting = Meeting.forLoggedEntry(
				request,
				logRequest.scheduledAt(),
				logRequest.durationMinutes(),
				logRequest.agenda(),
				logRequest.notes(),
				logRequest.outcomes(),
				logRequest.status());
		return MeetingResponse.fromEntity(meetingRepository.saveAndFlush(meeting));
	}

	@Transactional
	public MeetingResponse update(UUID meetingId, UpdateMeetingRequest request) {
		Meeting meeting = meetingRepository.findById(meetingId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
		if (request.notes() != null) {
			meeting.setNotes(request.notes());
		}
		if (request.outcomes() != null) {
			meeting.setOutcomes(request.outcomes());
		}
		if (request.status() != null) {
			meeting.setStatus(request.status());
		}
		return MeetingResponse.fromEntity(meetingRepository.saveAndFlush(meeting));
	}

	private MentorshipRequest requireMentorshipRequest(UUID mentorshipRequestId) {
		return mentorshipRequestRepository.findById(mentorshipRequestId)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Mentorship request not found: " + mentorshipRequestId));
	}
}
