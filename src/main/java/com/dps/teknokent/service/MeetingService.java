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
}
