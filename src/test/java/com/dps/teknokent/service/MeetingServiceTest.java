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
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	@Test
	void findAllWithoutOrganizationIdReturnsAllMeetingsOrderedBySchedule() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		Meeting meeting = namedMeeting(
				UUID.fromString("00000000-0000-0000-0000-000000000301"), demoOrganizationId, demoMentorId,
				"Roadmap review", MeetingStatus.SCHEDULED, 60);

		when(meetingRepository.findAllByOrderByScheduledAtDesc()).thenReturn(List.of(meeting));

		List<MeetingResponse> result = meetingService.findAll(null, null);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(meeting.getId());
		assertThat(result.get(0).status()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(result.get(0).agenda()).isEqualTo("Roadmap review");
	}

	@Test
	void findAllWithOrganizationIdReturnsOnlyThatOrganizationsMeetings() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		Meeting meeting = namedMeeting(
				UUID.fromString("00000000-0000-0000-0000-000000000302"), demoOrganizationId, demoMentorId,
				"MVP check-in", MeetingStatus.SCHEDULED, 90);

		when(meetingRepository.findByOrganizationIdOrderByScheduledAtDesc(demoOrganizationId))
				.thenReturn(List.of(meeting));

		List<MeetingResponse> result = meetingService.findAll(demoOrganizationId, null);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).organizationId()).isEqualTo(demoOrganizationId);
		assertThat(result.get(0).mentorId()).isEqualTo(demoMentorId);
	}

	@Test
	void findAllWithMentorIdReturnsThatMentorsMeetingsAcrossOrganizations() {
		UUID otherOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		Meeting meeting = namedMeeting(
				UUID.fromString("00000000-0000-0000-0000-000000000303"), otherOrganizationId, demoMentorId,
				"GreenLoop check-in", MeetingStatus.SCHEDULED, 60);

		when(meetingRepository.findByMentorIdOrderByScheduledAtDesc(demoMentorId))
				.thenReturn(List.of(meeting));

		List<MeetingResponse> result = meetingService.findAll(null, demoMentorId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).mentorId()).isEqualTo(demoMentorId);
		assertThat(result.get(0).organizationId()).isEqualTo(otherOrganizationId);
	}

	@Test
	void findAllPrefersMentorIdOverOrganizationIdWhenBothProvided() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		Meeting meeting = namedMeeting(
				UUID.fromString("00000000-0000-0000-0000-000000000304"), demoOrganizationId, demoMentorId,
				"Mentor-scoped view", MeetingStatus.SCHEDULED, 60);

		when(meetingRepository.findByMentorIdOrderByScheduledAtDesc(demoMentorId))
				.thenReturn(List.of(meeting));

		List<MeetingResponse> result = meetingService.findAll(demoOrganizationId, demoMentorId);

		assertThat(result).hasSize(1);
		verify(meetingRepository, never()).findByOrganizationIdOrderByScheduledAtDesc(any());
	}

	@Test
	void createSetsMentorWhenProvidedAndMentorRoleIsValid() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");

		Organization organization = namedOrganization(demoOrganizationId, "Teknokent");
		AppUser mentor = namedUser(demoMentorId, "Dr. Kemal Arslan", UserRole.MENTOR);
		CreateMeetingRequest request = new CreateMeetingRequest(
				demoOrganizationId,
				demoMentorId,
				Instant.parse("2026-09-15T16:00:00Z"),
				45,
				"Growth roadmap session",
				"Discuss next milestone",
				"Share final plan after review",
				"https://meet.google.com/demo-link",
				MeetingStatus.SCHEDULED);

		when(domainLookupService.requireUser(demoMentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(demoOrganizationId)).thenReturn(organization);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting saved = invocation.getArgument(0);
			saved.setId(meetingId);
			return saved;
		});

		MeetingResponse result = meetingService.create(request);

		assertThat(result.id()).isEqualTo(meetingId);
		assertThat(result.organizationId()).isEqualTo(demoOrganizationId);
		assertThat(result.mentorId()).isEqualTo(demoMentorId);
		assertThat(result.status()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	@Test
	void createThrowsWhenMentorIsNotMentorRole() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000202");

		AppUser nonMentor = namedUser(userId, "Elif Demir", UserRole.MENTEE);
		CreateMeetingRequest request = new CreateMeetingRequest(
				demoOrganizationId,
				userId,
				Instant.parse("2026-09-15T16:00:00Z"),
				45,
				"Growth roadmap session",
				null,
				null,
				null,
				MeetingStatus.SCHEDULED);

		when(domainLookupService.requireUser(userId)).thenReturn(nonMentor);

		assertThatThrownBy(() -> meetingService.create(request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTOR role");
	}

	private static Meeting namedMeeting(
			UUID id, UUID organizationId, UUID mentorId, String agenda, MeetingStatus status, int durationMinutes) {
		Meeting meeting = new Meeting();
		meeting.setId(id);
		meeting.setOrganization(namedOrganization(organizationId, "Teknokent"));
		meeting.setMentor(namedUser(mentorId, "Dr. Kemal Arslan", UserRole.MENTOR));
		meeting.setScheduledAt(Instant.parse("2026-09-15T16:00:00Z"));
		meeting.setDurationMinutes(durationMinutes);
		meeting.setAgenda(agenda);
		meeting.setStatus(status);
		return meeting;
	}

	private static Organization namedOrganization(UUID id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}

	private static AppUser namedUser(UUID id, String fullName, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(fullName);
		user.setRole(role);
		return user;
	}
}
