package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private DomainLookupService domainLookupService;

	private MeetingService meetingService;

	private final UUID mentorId = UUID.randomUUID();
	private final UUID otherMentorId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final UUID requestId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		meetingService = new MeetingService(meetingRepository, mentorshipRequestRepository, domainLookupService);
	}

	private AppUser mentor(UUID id) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(UserRole.MENTOR);
		return user;
	}

	private Organization organization() {
		Organization organization = new Organization();
		organization.setId(organizationId);
		return organization;
	}

	private MentorshipRequest acceptedRequest() {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(requestId);
		request.setMentor(mentor(mentorId));
		request.setOrganization(organization());
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		return request;
	}

	private LogMeetingRequest logRequest(UUID callerMentorId) {
		return new LogMeetingRequest(
				callerMentorId, Instant.now().minus(1, ChronoUnit.DAYS), 45, "Gündem", "Notlar", "Sonuçlar",
				MeetingStatus.COMPLETED);
	}

	@Test
	void logMeetingRejectsNonAcceptedRequest() {
		MentorshipRequest request = acceptedRequest();
		request.setStatus(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> meetingService.logMeeting(requestId, logRequest(mentorId)))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void logMeetingRejectsWrongMentor() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));

		assertThatThrownBy(() -> meetingService.logMeeting(requestId, logRequest(otherMentorId)))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void logMeetingRejectsUnknownRequest() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> meetingService.logMeeting(requestId, logRequest(mentorId)))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void logMeetingSavesEntryWithoutMentorshipRequestLink() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		when(meetingRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			meeting.setId(UUID.randomUUID());
			return meeting;
		});

		MeetingResponse response = meetingService.logMeeting(requestId, logRequest(mentorId));

		assertThat(response.mentorshipRequestId()).isNull();
		assertThat(response.mentorId()).isEqualTo(mentorId);
		assertThat(response.status()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(response.notes()).isEqualTo("Notlar");
	}

	@Test
	void findTimelineForRequestListsMeetingsByMentorAndOrganization() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, null, null, null, MeetingStatus.COMPLETED);
		meeting.setId(UUID.randomUUID());
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(mentorId, organizationId))
				.thenReturn(List.of(meeting));

		List<MeetingResponse> timeline = meetingService.findTimelineForRequest(requestId);

		assertThat(timeline).hasSize(1);
	}

	@Test
	void updateAppliesOnlyProvidedFields() {
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, "Gündem", "Eski not", null, MeetingStatus.SCHEDULED);
		UUID meetingId = UUID.randomUUID();
		meeting.setId(meetingId);
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(
				meetingId, new UpdateMeetingRequest("Yeni not", null, MeetingStatus.COMPLETED));

		assertThat(response.notes()).isEqualTo("Yeni not");
		assertThat(response.status()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(response.agenda()).isEqualTo("Gündem");
	}

	@Test
	void updateRejectsUnknownMeeting() {
		UUID meetingId = UUID.randomUUID();
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> meetingService.update(meetingId, new UpdateMeetingRequest("Not", null, null)))
				.isInstanceOf(ResourceNotFoundException.class);
	}
}
