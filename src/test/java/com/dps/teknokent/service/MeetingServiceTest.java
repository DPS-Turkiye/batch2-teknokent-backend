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
import com.dps.teknokent.dto.requests.InviteMeetingGuestRequest;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.dto.requests.SubmitMeetingFeedbackRequest;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
	@Mock
	private MeetingFeedbackRepository meetingFeedbackRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private NotificationService notificationService;
	@Mock
	private DocumentRequestService documentRequestService;

	private MeetingService meetingService;

	private final UUID mentorId = UUID.randomUUID();
	private final UUID otherMentorId = UUID.randomUUID();
	private final UUID organizationId = UUID.randomUUID();
	private final UUID requestId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		meetingService = new MeetingService(
				meetingRepository,
				mentorshipRequestRepository,
				domainLookupService,
				meetingFeedbackRepository,
				appUserRepository,
				notificationService,
				documentRequestService);
		lenient().when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(any()))
				.thenReturn(List.of());
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
				MeetingStatus.COMPLETED, null, null);
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
		void logMeetingSavesEntryLinkedToTheRequest() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			meeting.setId(UUID.randomUUID());
			return meeting;
		});

		MeetingResponse response = meetingService.logMeeting(requestId, logRequest(mentorId));

		assertThat(response.mentorshipRequestId()).isEqualTo(requestId);
		assertThat(response.mentorId()).isEqualTo(mentorId);
		assertThat(response.status()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(response.notes()).isEqualTo("Notlar");
		assertThat(response.transcribeRequested()).isFalse();
	}

	@Test
	void logMeetingPersistsTranscriptRequestAndMeetLink() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			meeting.setId(UUID.randomUUID());
			return meeting;
		});

		MeetingResponse response = meetingService.logMeeting(
				requestId,
				new LogMeetingRequest(
						mentorId,
						Instant.parse("2026-09-10T10:00:00Z"),
						45,
						null,
						null,
						null,
						MeetingStatus.SCHEDULED,
						"https://meet.google.com/abc-defg-hij",
						true));

		assertThat(response.transcribeRequested()).isTrue();
		assertThat(response.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(response.status()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	@Test
	void logMeetingUpdatesExistingRequestMeetingInsteadOfDuplicating() {
		MentorshipRequest request = acceptedRequest();
		Meeting existing = Meeting.forLoggedEntry(
				request,
				Instant.parse("2026-09-10T10:00:00Z"),
				45,
				"Eski",
				null,
				null,
				MeetingStatus.SCHEDULED,
				null,
				false);
		existing.setId(UUID.fromString("00000000-0000-0000-0000-000000000501"));
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.of(existing));
		when(meetingRepository.saveAndFlush(existing)).thenReturn(existing);

		MeetingResponse response = meetingService.logMeeting(
				requestId,
				new LogMeetingRequest(
						mentorId,
						Instant.parse("2026-09-10T11:00:00Z"),
						45,
						null,
						null,
						null,
						MeetingStatus.SCHEDULED,
						"https://meet.google.com/abc-defg-hij",
						true));

		assertThat(response.id()).isEqualTo(existing.getId());
		assertThat(response.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(response.transcribeRequested()).isTrue();
		assertThat(response.scheduledAt()).isEqualTo(Instant.parse("2026-09-10T11:00:00Z"));
	}

	@Test
	void findTimelineForRequestListsMeetingsByMentorAndOrganization() {
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, null, null, null, MeetingStatus.COMPLETED, null, false);
		meeting.setId(UUID.randomUUID());
		when(meetingRepository.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(mentorId, organizationId))
				.thenReturn(List.of(meeting));

		List<MeetingResponse> timeline = meetingService.findTimelineForRequest(requestId);

		assertThat(timeline).hasSize(1);
	}

	@Test
	void updateAppliesOnlyProvidedFields() {
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, "Gündem", "Eski not", null, MeetingStatus.SCHEDULED, null,
				false);
		UUID meetingId = UUID.randomUUID();
		meeting.setId(meetingId);
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
		when(meetingRepository.saveAll(any())).thenReturn(List.of(meeting));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(
				meetingId, new UpdateMeetingRequest("Yeni not", null, MeetingStatus.COMPLETED, null, null, null));

		assertThat(response.notes()).isEqualTo("Yeni not");
		assertThat(response.status()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(response.agenda()).isEqualTo("Gündem");
	}

	@Test
	void updateAppliesTranscriptRequestAndMeetLink() {
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, "Gündem", null, null, MeetingStatus.SCHEDULED, null,
				false);
		UUID meetingId = UUID.randomUUID();
		meeting.setId(meetingId);
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(
				meetingId,
				new UpdateMeetingRequest(null, null, null, null, "https://meet.google.com/abc-defg-hij", true));

		assertThat(response.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(response.transcribeRequested()).isTrue();
	}

	@Test
	void updateRejectsUnknownMeeting() {
		UUID meetingId = UUID.randomUUID();
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> meetingService.update(meetingId, new UpdateMeetingRequest("Not", null, null, null, null, null)))
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
				MeetingStatus.SCHEDULED,
				null,
				null);

		when(domainLookupService.requireUser(demoMentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(demoOrganizationId)).thenReturn(organization);
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				demoMentorId, demoOrganizationId, MentorshipRequestStatus.ACCEPTED))
				.thenReturn(Optional.empty());
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				demoMentorId, demoOrganizationId, MentorshipRequestStatus.PENDING))
				.thenReturn(Optional.empty());
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				demoMentorId, demoOrganizationId, MentorshipRequestStatus.MORE_INFO_REQUESTED))
				.thenReturn(Optional.empty());
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				demoMentorId, demoOrganizationId, MentorshipRequestStatus.COUNTER_PROPOSED))
				.thenReturn(Optional.empty());
		AppUser mentee = namedUser(UUID.fromString("00000000-0000-0000-0000-000000000102"), "Ayşe", UserRole.MENTEE);
		when(domainLookupService.requireOrganizationMentee(demoOrganizationId)).thenReturn(mentee);
		when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class))).thenAnswer(invocation -> {
			MentorshipRequest saved = invocation.getArgument(0);
			saved.setId(UUID.fromString("00000000-0000-0000-0000-000000000401"));
			return saved;
		});
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting saved = invocation.getArgument(0);
			saved.setId(meetingId);
			return saved;
		});
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(demoOrganizationId))
				.thenReturn(List.of(mentee));

		MeetingResponse result = meetingService.create(request);

		assertThat(result.id()).isEqualTo(meetingId);
		assertThat(result.organizationId()).isEqualTo(demoOrganizationId);
		assertThat(result.mentorId()).isEqualTo(demoMentorId);
		assertThat(result.status()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(result.transcribeRequested()).isFalse();
		assertThat(result.mentorshipRequestId())
				.isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000401"));
		verify(notificationService).create(
				eq(mentee),
				eq("Yeni görüşme planlandı"),
				org.mockito.ArgumentMatchers.contains("Teknokent"),
				eq("/mentorships?tab=active"));
	}

	@Test
	void createPersistsTranscriptRequest() {
		UUID demoOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID demoMentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000302");

		Organization organization = namedOrganization(demoOrganizationId, "Teknokent");
		AppUser mentor = namedUser(demoMentorId, "Dr. Kemal Arslan", UserRole.MENTOR);
		CreateMeetingRequest request = new CreateMeetingRequest(
				demoOrganizationId,
				demoMentorId,
				Instant.parse("2026-09-15T16:00:00Z"),
				45,
				"Growth roadmap session",
				null,
				null,
				"https://meet.google.com/demo-link",
				MeetingStatus.SCHEDULED,
				true,
				null);

		when(domainLookupService.requireUser(demoMentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(demoOrganizationId)).thenReturn(organization);
		MentorshipRequest accepted = new MentorshipRequest();
		accepted.setId(UUID.fromString("00000000-0000-0000-0000-000000000401"));
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				demoMentorId, demoOrganizationId, MentorshipRequestStatus.ACCEPTED))
				.thenReturn(Optional.of(accepted));
		when(meetingRepository.findByMentorshipRequestId(accepted.getId())).thenReturn(Optional.empty());
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting saved = invocation.getArgument(0);
			saved.setId(meetingId);
			return saved;
		});

		MeetingResponse result = meetingService.create(request);

		assertThat(result.transcribeRequested()).isTrue();
		assertThat(result.mentorshipRequestId()).isEqualTo(accepted.getId());
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
				MeetingStatus.SCHEDULED,
				null,
				null);

		when(domainLookupService.requireUser(userId)).thenReturn(nonMentor);

		assertThatThrownBy(() -> meetingService.create(request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTOR role");
	}

	@Test
	void inviteGuestCopiesTheMeetingOntoAnotherStartup() {
		UUID sourceId = UUID.randomUUID();
		UUID guestOrgId = UUID.randomUUID();
		Instant at = Instant.parse("2026-09-15T16:00:00Z");
		Meeting source = namedMeeting(sourceId, organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED, 60);
		source.setMeetLink("https://meet.google.com/abc-defg-hij");
		source.setScheduledAt(at);
		Organization guestOrg = namedOrganization(guestOrgId, "NovaMed");

		when(meetingRepository.findById(sourceId)).thenReturn(Optional.of(source));
		when(domainLookupService.requireOrganization(guestOrgId)).thenReturn(guestOrg);
		when(meetingRepository.findByMentorIdAndOrganizationIdAndScheduledAt(mentorId, guestOrgId, at))
				.thenReturn(Optional.empty());
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting saved = invocation.getArgument(0);
			saved.setId(UUID.randomUUID());
			return saved;
		});

		MeetingResponse result = meetingService.inviteGuest(sourceId, new InviteMeetingGuestRequest(guestOrgId, null));

		assertThat(result.organizationId()).isEqualTo(guestOrgId);
		assertThat(result.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(result.mentorId()).isEqualTo(mentorId);
	}

	@Test
	void inviteGuestReturnsTheExistingCopyInsteadOfDuplicating() {
		UUID sourceId = UUID.randomUUID();
		Meeting source = namedMeeting(sourceId, organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED, 60);
		Meeting existing = namedMeeting(UUID.randomUUID(), organizationId, otherMentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);

		when(meetingRepository.findById(sourceId)).thenReturn(Optional.of(source));
		when(domainLookupService.requireUser(otherMentorId))
				.thenReturn(namedUser(otherMentorId, "Ayşe Kaya", UserRole.MENTOR));
		when(meetingRepository.findByMentorIdAndOrganizationIdAndScheduledAt(
				otherMentorId, organizationId, source.getScheduledAt())).thenReturn(Optional.of(existing));

		MeetingResponse result = meetingService.inviteGuest(sourceId, new InviteMeetingGuestRequest(null, otherMentorId));

		assertThat(result.id()).isEqualTo(existing.getId());
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void deleteRoomRemovesEveryCopyWithTheSameMeetLink() {
		Meeting first = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED,
				60);
		first.setMeetLink("https://meet.google.com/abc-defg-hij");
		Meeting second = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), otherMentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		second.setMeetLink("https://meet.google.com/abc-defg-hij");
		second.setScheduledAt(first.getScheduledAt());

		when(meetingRepository.findById(first.getId())).thenReturn(Optional.of(first));
		when(meetingRepository.findByMeetLinkAndScheduledAt(first.getMeetLink(), first.getScheduledAt()))
				.thenReturn(List.of(first, second));

		meetingService.deleteRoom(first.getId());

		verify(meetingRepository).deleteAll(List.of(first, second));
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

	@Test
	void markBotStoppedCompletesTheRunningSlotAndLeavesLaterOnesAlone() {
		Meeting running = linkedMeeting(Instant.now().minus(15, ChronoUnit.MINUTES), 120);
		Meeting nextWeek = linkedMeeting(Instant.now().plus(7, ChronoUnit.DAYS), 120);
		when(meetingRepository.findByStatusAndMeetLinkContainingIgnoreCase(
				MeetingStatus.SCHEDULED, "abc-defg-hij")).thenReturn(List.of(running, nextWeek));

		assertThat(meetingService.markBotStopped("abc-defg-hij")).containsExactly(running.getId());

		assertThat(running.getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(running.getBotDispatchedAt()).isNotNull();
		assertThat(nextWeek.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(nextWeek.getBotDispatchedAt()).isNull();
		verify(meetingRepository).saveAll(List.of(running));
	}

	@Test
	void markBotStoppedIgnoresSlotsTheDispatcherHasAlreadyGivenUpOn() {
		Meeting longGone = linkedMeeting(Instant.now().minus(5, ChronoUnit.HOURS), 60);
		when(meetingRepository.findByStatusAndMeetLinkContainingIgnoreCase(
				MeetingStatus.SCHEDULED, "abc-defg-hij")).thenReturn(List.of(longGone));

		assertThat(meetingService.markBotStopped("abc-defg-hij")).isEmpty();
		assertThat(longGone.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	@Test
	void markBotStoppedIgnoresABlankMeetingId() {
		assertThat(meetingService.markBotStopped(" ")).isEmpty();
		verify(meetingRepository, never()).findByStatusAndMeetLinkContainingIgnoreCase(any(), any());
	}

	private static Meeting linkedMeeting(Instant scheduledAt, int durationMinutes) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMeetLink("https://meet.google.com/abc-defg-hij");
		meeting.setScheduledAt(scheduledAt);
		meeting.setDurationMinutes(durationMinutes);
		meeting.setTranscribeRequested(true);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	@Test
	void submitFeedback_storesRatingsAndBenefitsForTheMentor() {
		UUID meetingId = UUID.randomUUID();
		AppUser author = mentor(mentorId);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setMentor(author);

		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(author);
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meetingId, mentorId))
				.thenReturn(Optional.empty());
		when(meetingFeedbackRepository.saveAndFlush(any(MeetingFeedback.class)))
				.thenAnswer(invocation -> {
					MeetingFeedback saved = invocation.getArgument(0);
					saved.setId(UUID.randomUUID());
					return saved;
				});

		var response = meetingService.submitFeedback(meetingId, mentorId,
				new SubmitMeetingFeedbackRequest(List.of(5, 4, 5), List.of("Finansal modeli anlamak")));

		assertThat(response.ratings()).containsExactly(5, 4, 5);
		assertThat(response.benefits()).containsExactly("Finansal modeli anlamak");
	}

	@Test
	void submitFeedback_rejectsWhenAuthorAlreadySubmitted() {
		UUID meetingId = UUID.randomUUID();
		AppUser author = mentor(mentorId);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setMentor(author);

		MeetingFeedback existing = new MeetingFeedback();
		existing.setId(UUID.randomUUID());
		existing.setMeeting(meeting);
		existing.setAuthor(author);
		existing.setCreatedAt(Instant.now());
		existing.getRatings().addAll(List.of(1, 1, 1));

		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(author);
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meetingId, mentorId))
				.thenReturn(Optional.of(existing));

		assertThatThrownBy(() -> meetingService.submitFeedback(meetingId, mentorId,
				new SubmitMeetingFeedbackRequest(List.of(5, 5, 5), List.of())))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("already submitted");
		verify(meetingFeedbackRepository, never()).saveAndFlush(any(MeetingFeedback.class));
	}

	@Test
	void submitFeedback_rejectsSomebodyWhoWasNotInTheMeeting() {
		UUID meetingId = UUID.randomUUID();
		AppUser outsider = mentor(otherMentorId);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setMentor(mentor(mentorId));

		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(otherMentorId)).thenReturn(outsider);

		assertThatThrownBy(() -> meetingService.submitFeedback(meetingId, otherMentorId,
				new SubmitMeetingFeedbackRequest(List.of(5, 5, 5), List.of())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("participant");
	}
}
