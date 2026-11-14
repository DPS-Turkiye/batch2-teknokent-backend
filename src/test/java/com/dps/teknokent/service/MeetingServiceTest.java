package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.InviteMeetingGuestRequest;
import com.dps.teknokent.dto.requests.LogMeetingRequest;
import com.dps.teknokent.dto.requests.SubmitMeetingFeedbackRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

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

	@Mock
	private ApplicationEventPublisher eventPublisher;

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
				documentRequestService,
				new TranscriptBotCapacityService(meetingRepository, 2),
				eventPublisher);
		lenient().when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(any()))
				.thenReturn(List.of());
	}

	private AppUser mentor(UUID id) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(UserRole.MENTOR);
		return user;
	}

	private Meeting heldCandidate(UUID mentor, Instant at, String meetLink, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor(mentor));
		meeting.setOrganization(organization());
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(60);
		meeting.setMeetLink(meetLink);
		meeting.setStatus(status);
		return meeting;
	}

	@Test
	void confirmHeldCoversTheGroupSessionButNotACancelledSeat() {
		Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
		Meeting meeting = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		Meeting otherTeam = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		Meeting cancelledSeat = heldCandidate(mentorId, at, null, MeetingStatus.CANCELLED);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, at))
				.thenReturn(List.of(meeting, otherTeam, cancelledSeat));

		MeetingResponse response = meetingService.confirmHeld(meeting.getId(), mentorId, true);

		assertThat(response.heldConfirmedAt()).isNotNull();
		assertThat(otherTeam.getHeldConfirmedAt()).isEqualTo(meeting.getHeldConfirmedAt());
		assertThat(cancelledSeat.getHeldConfirmedAt()).isNull();
	}

	@Test
	void confirmHeldLeavesOlderMeetingsOnTheSameMeetLinkAlone() {
		String link = "https://meet.google.com/abc-defg-hij";
		Instant at = Instant.now().minus(1, ChronoUnit.HOURS);
		Meeting meeting = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		Meeting lastWeek = heldCandidate(mentorId, at.minus(7, ChronoUnit.DAYS), link, MeetingStatus.SCHEDULED);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("abc-defg-hij"))
				.thenReturn(List.of(meeting, lastWeek));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, at)).thenReturn(List.of(meeting));

		meetingService.confirmHeld(meeting.getId(), mentorId, true);

		assertThat(meeting.getHeldConfirmedAt()).isNotNull();
		assertThat(lastWeek.getHeldConfirmedAt()).isNull();
	}

	@Test
	void confirmingHeldChecksTheAchievementsOfTheMentorAndOfMenteesWhoAlreadyGaveFeedback() {
		Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
		Meeting meeting = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		UUID menteeId = UUID.randomUUID();
		MeetingFeedback earlier = new MeetingFeedback();
		earlier.setAuthor(namedUser(menteeId, "Ece Öğrenci", UserRole.MENTEE));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, at)).thenReturn(List.of(meeting));
		when(meetingFeedbackRepository.findByMeetingIdInAndAuthorRole(List.of(meeting.getId()), UserRole.MENTEE))
				.thenReturn(List.of(earlier));

		meetingService.confirmHeld(meeting.getId(), mentorId, true);

		verify(eventPublisher).publishEvent(new AchievementsMayChangeEvent(Set.of(mentorId, menteeId)));
	}

	@Test
	void undoingHeldChecksNoAchievements() {
		Instant at = Instant.now().minus(1, ChronoUnit.DAYS);
		Meeting meeting = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		meeting.setHeldConfirmedAt(at);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, at)).thenReturn(List.of(meeting));

		meetingService.confirmHeld(meeting.getId(), mentorId, false);

		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	@Test
	void confirmHeldUndoClearsTheConfirmation() {
		Instant at = Instant.now().minus(1, ChronoUnit.DAYS);
		Meeting meeting = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		meeting.setHeldConfirmedAt(at);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, at)).thenReturn(List.of(meeting));

		assertThat(meetingService.confirmHeld(meeting.getId(), mentorId, false).heldConfirmedAt()).isNull();
	}

	@Test
	void confirmHeldRefusesAnotherMentorAFutureMeetingAndACancelledOne() {
		Meeting past = heldCandidate(mentorId, Instant.now().minus(1, ChronoUnit.HOURS), null, MeetingStatus.SCHEDULED);
		Meeting future = heldCandidate(mentorId, Instant.now().plus(1, ChronoUnit.HOURS), null,
				MeetingStatus.SCHEDULED);
		Meeting cancelled = heldCandidate(mentorId, Instant.now().minus(1, ChronoUnit.HOURS), null,
				MeetingStatus.CANCELLED);
		when(meetingRepository.findById(past.getId())).thenReturn(Optional.of(past));
		when(meetingRepository.findById(future.getId())).thenReturn(Optional.of(future));
		when(meetingRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

		assertThatThrownBy(() -> meetingService.confirmHeld(past.getId(), otherMentorId, true))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> meetingService.confirmHeld(future.getId(), mentorId, true))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> meetingService.confirmHeld(cancelled.getId(), mentorId, true))
				.isInstanceOf(InvalidRequestException.class);
		verify(meetingRepository, never()).saveAll(any());
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

	private void recordingBotsBusyAt(Instant at) {
		Meeting first = new Meeting();
		first.setScheduledAt(at);
		first.setDurationMinutes(60);
		first.setMeetLink("https://meet.google.com/busy-one");
		Meeting second = new Meeting();
		second.setScheduledAt(at);
		second.setDurationMinutes(60);
		second.setMeetLink("https://meet.google.com/busy-two");
		when(meetingRepository.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(
				eq(MeetingStatus.SCHEDULED), any(), any())).thenReturn(List.of(first, second));
	}

	private LogMeetingRequest recordedLogRequest(Instant at, String notes) {
		return new LogMeetingRequest(
				mentorId, at, 45, null, notes, null, MeetingStatus.SCHEDULED, "https://meet.google.com/abc-defg-hij",
				true);
	}

	@Test
	void logMeetingRefusesARecordedMeetingWhenEveryBotIsBusy() {
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(acceptedRequest()));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());
		recordingBotsBusyAt(at);

		assertThatThrownBy(() -> meetingService.logMeeting(requestId, recordedLogRequest(at, null)))
				.isInstanceOf(ConflictException.class);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void logMeetingLetsNotesBeEditedOnAnAlreadyBookedRecordingInAFullSlot() {
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		MentorshipRequest request = acceptedRequest();
		Meeting existing = Meeting.forLoggedEntry(
				request, at, 45, null, null, null, MeetingStatus.SCHEDULED, "https://meet.google.com/abc-defg-hij",
				true);
		existing.setId(UUID.randomUUID());
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.of(existing));
		when(meetingRepository.saveAndFlush(existing)).thenReturn(existing);

		MeetingResponse response = meetingService.logMeeting(requestId, recordedLogRequest(at, "Yeni not"));

		assertThat(response.notes()).isEqualTo("Yeni not");
		verify(meetingRepository, never()).findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(),
				any());
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

		MeetingResponse response = meetingService.update(meetingId, null,
				new UpdateMeetingRequest("Yeni not", null, MeetingStatus.COMPLETED, null, null, null));

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

		MeetingResponse response = meetingService.update(meetingId, null,
				new UpdateMeetingRequest(null, null, null, null, "https://meet.google.com/abc-defg-hij", true));

		assertThat(response.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
		assertThat(response.transcribeRequested()).isTrue();
	}

	@Test
	void updateRejectsUnknownMeeting() {
		UUID meetingId = UUID.randomUUID();
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.empty());

		assertThatThrownBy(
				() -> meetingService.update(meetingId, null,
						new UpdateMeetingRequest("Not", null, null, null, null, null)))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	// A session without a startup sends a blank organization, which arrives here as null.
	@Test
	void findAllWithoutAScopeRefusesInsteadOfListingEveryMeeting() {
		assertThatThrownBy(() -> meetingService.findAll(null, null))
				.isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(meetingRepository);
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
				eq(NotificationCategory.MEETING_CHANGE),
				eq("Yeni görüşme planlandı"),
				org.mockito.ArgumentMatchers
						.contains("Teknokent için yeni bir görüşme planladı · 15 Eylül Salı · 19:00."),
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

		MeetingResponse result = meetingService.inviteGuest(sourceId, null,
				new InviteMeetingGuestRequest(guestOrgId, null));

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

		MeetingResponse result = meetingService.inviteGuest(sourceId, null,
				new InviteMeetingGuestRequest(null, otherMentorId));

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

		meetingService.deleteRoom(first.getId(), null);

		verify(meetingRepository).deleteAll(List.of(first, second));
	}

	@Test
	void deleteRoomLeavesAnotherTeamsMeetingInTheSameMeetRoomAlone() {
		Meeting thisSession = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		thisSession.setMeetLink("https://meet.google.com/abc-defg-hij");
		Meeting nextWeek = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		nextWeek.setMeetLink("https://meet.google.com/abc-defg-hij");
		nextWeek.setScheduledAt(thisSession.getScheduledAt().plus(7, ChronoUnit.DAYS));
		when(meetingRepository.findById(thisSession.getId())).thenReturn(Optional.of(thisSession));
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("abc-defg-hij"))
				.thenReturn(List.of(thisSession, nextWeek));
		when(meetingRepository.findByMeetLinkAndScheduledAt(thisSession.getMeetLink(), thisSession.getScheduledAt()))
				.thenReturn(List.of(thisSession));

		meetingService.deleteRoom(thisSession.getId(), mentorId);

		verify(meetingRepository).deleteAll(List.of(thisSession));
	}

	@Test
	void deleteRoomRefusesATeamMember() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		UUID menteeId = UUID.randomUUID();
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(menteeId)).thenReturn(teamMember(menteeId));

		assertThatThrownBy(() -> meetingService.deleteRoom(meeting.getId(), menteeId))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(meetingRepository, never()).deleteAll(any());
	}

	@Test
	void deleteRoomLetsAnAdminRemoveTheMeeting() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		UUID adminId = UUID.randomUUID();
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(adminId)).thenReturn(namedUser(adminId, "Yönetici", UserRole.ADMIN));

		meetingService.deleteRoom(meeting.getId(), adminId);

		verify(meetingRepository).deleteAll(List.of(meeting));
	}

	@Test
	void findRoomAndInviteGuestRefuseSomeoneWhoIsNotTheMentor() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(otherMentorId)).thenReturn(mentor(otherMentorId));

		assertThatThrownBy(() -> meetingService.findRoom(meeting.getId(), otherMentorId))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> meetingService.inviteGuest(meeting.getId(), otherMentorId,
				new InviteMeetingGuestRequest(UUID.randomUUID(), null)))
				.isInstanceOf(ResponseStatusException.class);
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void updateRefusesATeamMemberWhoChangesTheLinkTheStatusOrTheRecording() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		UUID menteeId = UUID.randomUUID();
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(menteeId)).thenReturn(teamMember(menteeId));

		for (UpdateMeetingRequest change : List.of(
				new UpdateMeetingRequest(null, null, null, null, "https://meet.google.com/zzz-zzzz-zzz", null),
				new UpdateMeetingRequest(null, null, MeetingStatus.CANCELLED, null, null, null),
				new UpdateMeetingRequest(null, null, null, null, null, true))) {
			assertThatThrownBy(() -> meetingService.update(meeting.getId(), menteeId, change))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		}
		assertThat(meeting.getMeetLink()).isNull();
		assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		verify(meetingRepository, never()).saveAndFlush(any(Meeting.class));
	}

	@Test
	void updateRefusesSomeoneOutsideTheMeeting() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		UUID outsiderId = UUID.randomUUID();
		AppUser outsider = namedUser(outsiderId, "Başka Ekip", UserRole.MENTEE);
		outsider.setOrganization(namedOrganization(UUID.randomUUID(), "Başka"));
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(outsiderId)).thenReturn(outsider);

		assertThatThrownBy(() -> meetingService.update(meeting.getId(), outsiderId,
				new UpdateMeetingRequest("Not", null, null, null, null, null)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void updateLetsATeamMemberWriteNotesAndCloseTheMeetingFromTheAssistant() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		UUID menteeId = UUID.randomUUID();
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(menteeId)).thenReturn(teamMember(menteeId));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(meeting.getId(), menteeId,
				new UpdateMeetingRequest("Notlar", "Sonuç", MeetingStatus.COMPLETED, true, null, null));

		assertThat(response.notes()).isEqualTo("Notlar");
		assertThat(response.status()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(response.botDispatchedAt()).isNotNull();
	}

	@Test
	void updateLetsTheMentorChangeTheLink() {
		Meeting meeting = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor(mentorId));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(meeting.getId(), mentorId,
				new UpdateMeetingRequest(null, null, null, null, "https://meet.google.com/abc-defg-hij", null));

		assertThat(response.meetLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
	}

	@Test
	void updateClosesOnlyTheCopiesOfThisSessionNotOtherMeetingsInTheSameRoom() {
		Meeting thisSession = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		thisSession.setMeetLink("https://meet.google.com/abc-defg-hij");
		Meeting guestCopy = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		guestCopy.setMeetLink("https://meet.google.com/abc-defg-hij?authuser=1");
		Meeting nextWeek = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		nextWeek.setMeetLink("https://meet.google.com/abc-defg-hij");
		nextWeek.setScheduledAt(thisSession.getScheduledAt().plus(7, ChronoUnit.DAYS));
		when(meetingRepository.findById(thisSession.getId())).thenReturn(Optional.of(thisSession));
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("abc-defg-hij"))
				.thenReturn(List.of(thisSession, guestCopy, nextWeek));
		when(meetingRepository.saveAndFlush(thisSession)).thenReturn(thisSession);

		meetingService.update(thisSession.getId(), null,
				new UpdateMeetingRequest(null, null, MeetingStatus.COMPLETED, true, null, null));

		assertThat(guestCopy.getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(guestCopy.getBotDispatchedAt()).isNotNull();
		assertThat(nextWeek.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(nextWeek.getBotDispatchedAt()).isNull();
	}

	private AppUser teamMember(UUID id) {
		AppUser member = namedUser(id, "Ece Öğrenci", UserRole.MENTEE);
		member.setOrganization(namedOrganization(organizationId, "Teknokent"));
		return member;
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
	void markBotStoppedClosesAnEndedSlotThatHadABot() {
		Meeting recorded = linkedMeeting(Instant.now().minus(5, ChronoUnit.HOURS), 60);
		recorded.setBotDispatchedAt(Instant.now().minus(5, ChronoUnit.HOURS));
		Meeting nextWeek = linkedMeeting(Instant.now().plus(7, ChronoUnit.DAYS), 60);
		nextWeek.setBotDispatchedAt(Instant.now());
		when(meetingRepository.findByStatusAndMeetLinkContainingIgnoreCase(
				MeetingStatus.SCHEDULED, "abc-defg-hij")).thenReturn(List.of(recorded, nextWeek));

		assertThat(meetingService.markBotStopped("abc-defg-hij")).containsExactly(recorded.getId());
		assertThat(recorded.getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(nextWeek.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
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
		verify(eventPublisher).publishEvent(new AchievementsMayChangeEvent(Set.of(mentorId)));
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

	@Test
	void ratedMeetingIds_returnsOnlyTheMeetingsTheAuthorRatedInOneLookup() {
		UUID authorId = UUID.randomUUID();
		UUID rated = UUID.randomUUID();
		UUID unrated = UUID.randomUUID();
		Meeting meeting = new Meeting();
		meeting.setId(rated);
		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setMeeting(meeting);
		when(meetingFeedbackRepository.findByMeetingIdInAndAuthorId(List.of(rated, unrated), authorId))
				.thenReturn(List.of(feedback));

		assertThat(meetingService.ratedMeetingIds(List.of(rated, unrated), authorId)).containsExactly(rated);
	}

	@Test
	void ratedMeetingIds_skipsTheQueryForNoMeetingsAndRejectsTooMany() {
		UUID authorId = UUID.randomUUID();
		assertThat(meetingService.ratedMeetingIds(List.of(), authorId)).isEmpty();
		List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();

		assertThatThrownBy(() -> meetingService.ratedMeetingIds(tooMany, authorId))
				.isInstanceOf(InvalidRequestException.class);
		verify(meetingFeedbackRepository, never()).findByMeetingIdInAndAuthorId(any(), any());
	}

	private CreateMeetingRequest createRequest(UUID mentor, String meetLink, Boolean transcribe,
			List<String> documents) {
		return new CreateMeetingRequest(
				organizationId, mentor, Instant.parse("2026-09-15T16:00:00Z"), 45, "Yol haritası", null, null,
				meetLink, MeetingStatus.SCHEDULED, transcribe, documents);
	}

	private void savingMeetingsAsTheyAre() {
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private MentorshipRequest requestInStatus(MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(UUID.randomUUID());
		request.setStatus(status);
		return request;
	}

	@Test
	void createWithoutAMentorSavesAStandaloneMeetingAndTellsOnlyTheMentees() {
		AppUser mentee = namedUser(UUID.randomUUID(), "Ayşe", UserRole.MENTEE);
		AppUser teammateWhoMentors = namedUser(UUID.randomUUID(), "Bora", UserRole.MENTOR);
		when(domainLookupService.requireOrganization(organizationId))
				.thenReturn(namedOrganization(organizationId, "Teknokent"));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId))
				.thenReturn(List.of(mentee, teammateWhoMentors));
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.create(createRequest(null, null, null, List.of("Sunum")));

		assertThat(result.mentorId()).isNull();
		assertThat(result.mentorshipRequestId()).isNull();
		verify(notificationService).create(
				mentee,
				NotificationCategory.MEETING_CHANGE,
				"Yeni görüşme planlandı",
				"Mentörünüz, Teknokent için yeni bir görüşme planladı · 15 Eylül Salı · 19:00.",
				"/mentorships?tab=active");
		verify(notificationService, never()).create(eq(teammateWhoMentors), any(), any(), any(), any());
		verifyNoInteractions(mentorshipRequestRepository, documentRequestService);
	}

	@Test
	void createAcceptsAWaitingRequestWhenTheAcceptedOneAlreadyHasItsMeeting() {
		AppUser mentor = namedUser(mentorId, "Dr. Kemal Arslan", UserRole.MENTOR);
		MentorshipRequest alreadyMet = requestInStatus(MentorshipRequestStatus.ACCEPTED);
		MentorshipRequest waiting = requestInStatus(MentorshipRequestStatus.PENDING);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(organizationId))
				.thenReturn(namedOrganization(organizationId, "Teknokent"));
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				mentorId, organizationId, MentorshipRequestStatus.ACCEPTED)).thenReturn(Optional.of(alreadyMet));
		when(meetingRepository.findByMentorshipRequestId(alreadyMet.getId())).thenReturn(Optional.of(new Meeting()));
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				mentorId, organizationId, MentorshipRequestStatus.PENDING)).thenReturn(Optional.of(waiting));
		when(meetingRepository.findByMentorshipRequestId(waiting.getId())).thenReturn(Optional.empty());
		when(mentorshipRequestRepository.saveAndFlush(waiting)).thenReturn(waiting);
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.create(
				createRequest(mentorId, null, null, List.of("Finansal tablo")));

		assertThat(result.mentorshipRequestId()).isEqualTo(waiting.getId());
		assertThat(waiting.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(waiting.getMentorMessage()).isEqualTo("Takvimden oluşturuldu");
		verify(domainLookupService, never()).requireOrganizationMentee(any());
		verify(documentRequestService).saveTitlesOnMeeting(any(Meeting.class), eq(mentor),
				eq(List.of("Finansal tablo")));
	}

	@Test
	void createStartsANewMentorshipWhenEveryExistingRequestAlreadyHasAMeeting() {
		AppUser mentor = namedUser(mentorId, "Dr. Kemal Arslan", UserRole.MENTOR);
		AppUser mentee = namedUser(UUID.randomUUID(), "Ayşe", UserRole.MENTEE);
		MentorshipRequest waiting = requestInStatus(MentorshipRequestStatus.PENDING);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(organizationId))
				.thenReturn(namedOrganization(organizationId, "Teknokent"));
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				eq(mentorId), eq(organizationId), any())).thenReturn(Optional.empty());
		when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
				mentorId, organizationId, MentorshipRequestStatus.PENDING)).thenReturn(Optional.of(waiting));
		when(meetingRepository.findByMentorshipRequestId(waiting.getId())).thenReturn(Optional.of(new Meeting()));
		when(domainLookupService.requireOrganizationMentee(organizationId)).thenReturn(mentee);
		when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		savingMeetingsAsTheyAre();

		meetingService.create(createRequest(mentorId, null, null, null));

		assertThat(waiting.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestRepository, never()).saveAndFlush(waiting);
		verify(mentorshipRequestRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
				created -> created.getStatus() == MentorshipRequestStatus.ACCEPTED
						&& created.getRequester().equals(mentee)
						&& "Yol haritası".equals(created.getSupportArea())));
	}

	// One bot joins per Meet link, so a second meeting on the link shares it and never needs a free bot.
	@Test
	void createOnALinkThatAlreadyHasItsBotSharesItInsteadOfAskingForAFreeOne() {
		String link = "https://meet.google.com/abc-defg-hij";
		Instant dispatched = Instant.parse("2026-09-15T15:55:00Z");
		Meeting inTheRoom = new Meeting();
		inTheRoom.setBotDispatchedAt(dispatched);
		when(domainLookupService.requireOrganization(organizationId))
				.thenReturn(namedOrganization(organizationId, "Teknokent"));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, Instant.parse("2026-09-15T16:00:00Z")))
				.thenReturn(List.of(new Meeting(), inTheRoom));
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.create(createRequest(null, link, true, null));

		assertThat(result.botDispatchedAt()).isEqualTo(dispatched);
		verify(meetingRepository, never()).findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(),
				any());
	}

	@Test
	void createInAMentorsPersonalRoomStillGetsItsOwnBotWhenAnEarlierMeetingThereHadOne() {
		String link = "https://meet.google.com/abc-defg-hij";
		when(domainLookupService.requireOrganization(organizationId))
				.thenReturn(namedOrganization(organizationId, "Teknokent"));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, Instant.parse("2026-09-15T16:00:00Z")))
				.thenReturn(List.of());
		when(meetingRepository.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(), any()))
				.thenReturn(List.of());
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.create(createRequest(null, link, true, null));

		assertThat(result.botDispatchedAt()).isNull();
	}

	@Test
	void findRoomLeavesOutOtherSessionsInTheSameMeetRoom() {
		Meeting thisSession = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		thisSession.setMeetLink("https://meet.google.com/abc-defg-hij");
		Meeting otherTeamLastWeek = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), mentorId, "Eski",
				MeetingStatus.COMPLETED, 60);
		otherTeamLastWeek.setMeetLink("https://meet.google.com/abc-defg-hij?authuser=0");
		otherTeamLastWeek.setScheduledAt(thisSession.getScheduledAt().minus(7, ChronoUnit.DAYS));
		when(meetingRepository.findById(thisSession.getId())).thenReturn(Optional.of(thisSession));
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("abc-defg-hij"))
				.thenReturn(List.of(thisSession, otherTeamLastWeek));

		assertThat(meetingService.findRoom(thisSession.getId(), mentorId)).extracting(MeetingResponse::id)
				.containsExactly(thisSession.getId());
	}

	@Test
	void findRoomListsEveryTeamsCopyOfTheSameSession() {
		Meeting first = namedMeeting(UUID.randomUUID(), organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED,
				60);
		first.setMeetLink("https://meet.google.com/abc-defg-hij");
		Meeting second = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), mentorId, "Gündem",
				MeetingStatus.SCHEDULED, 60);
		second.setMeetLink("https://meet.google.com/abc-defg-hij?authuser=1");
		Meeting otherSite = namedMeeting(UUID.randomUUID(), UUID.randomUUID(), otherMentorId, "Başka",
				MeetingStatus.SCHEDULED, 60);
		otherSite.setMeetLink("https://example.com/abc-defg-hij");
		when(meetingRepository.findById(first.getId())).thenReturn(Optional.of(first));
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("abc-defg-hij"))
				.thenReturn(List.of(first, second, otherSite));

		assertThat(meetingService.findRoom(first.getId(), null)).extracting(MeetingResponse::id)
				.containsExactly(first.getId(), second.getId());
	}

	@Test
	void findRoomRejectsAnUnknownMeeting() {
		UUID unknown = UUID.randomUUID();
		when(meetingRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> meetingService.findRoom(unknown, null)).isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void inviteGuestNeedsAStartupOrAMentorToInvite() {
		assertThatThrownBy(() -> meetingService.inviteGuest(UUID.randomUUID(), null,
				new InviteMeetingGuestRequest(null, null))).isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(meetingRepository);
	}

	@Test
	void inviteGuestRefusesAGuestMentorWithoutTheMentorRole() {
		UUID sourceId = UUID.randomUUID();
		UUID menteeId = UUID.randomUUID();
		Meeting source = namedMeeting(sourceId, organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED, 60);
		when(meetingRepository.findById(sourceId)).thenReturn(Optional.of(source));
		when(domainLookupService.requireUser(menteeId)).thenReturn(namedUser(menteeId, "Elif Demir", UserRole.MENTEE));

		assertThatThrownBy(
				() -> meetingService.inviteGuest(sourceId, null, new InviteMeetingGuestRequest(null, menteeId)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTOR role");
		verify(meetingRepository, never()).save(any(Meeting.class));
	}

	@Test
	void inviteGuestOnAMeetingWithoutAMentorCopiesItForTheOtherStartup() {
		UUID sourceId = UUID.randomUUID();
		UUID guestOrgId = UUID.randomUUID();
		Meeting source = namedMeeting(sourceId, organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED, 60);
		source.setMentor(null);
		when(meetingRepository.findById(sourceId)).thenReturn(Optional.of(source));
		when(domainLookupService.requireOrganization(guestOrgId)).thenReturn(namedOrganization(guestOrgId, "NovaMed"));
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.inviteGuest(sourceId, null,
				new InviteMeetingGuestRequest(guestOrgId, null));

		assertThat(result.organizationId()).isEqualTo(guestOrgId);
		assertThat(result.mentorId()).isNull();
		assertThat(result.scheduledAt()).isEqualTo(source.getScheduledAt());
		verify(meetingRepository, never()).findByMentorIdAndOrganizationIdAndScheduledAt(any(), any(), any());
	}

	@Test
	void inviteGuestCopyKeepsTheBotAlreadySentToTheRoom() {
		UUID sourceId = UUID.randomUUID();
		UUID guestOrgId = UUID.randomUUID();
		Instant dispatched = Instant.parse("2026-09-15T15:55:00Z");
		Meeting source = namedMeeting(sourceId, organizationId, mentorId, "Gündem", MeetingStatus.SCHEDULED, 60);
		source.setMeetLink("https://meet.google.com/abc-defg-hij");
		source.setTranscribeRequested(true);
		source.setBotDispatchedAt(dispatched);
		when(meetingRepository.findById(sourceId)).thenReturn(Optional.of(source));
		when(domainLookupService.requireOrganization(guestOrgId)).thenReturn(namedOrganization(guestOrgId, "NovaMed"));
		savingMeetingsAsTheyAre();

		MeetingResponse result = meetingService.inviteGuest(sourceId, null,
				new InviteMeetingGuestRequest(guestOrgId, null));

		assertThat(result.botDispatchedAt()).isEqualTo(dispatched);
		assertThat(result.transcribeRequested()).isTrue();
		verify(meetingRepository, never()).findByMeetLinkAndScheduledAt(any(), any());
	}

	@Test
	void logMeetingChangesOnlyWhatWasSentOnAnExistingMeeting() {
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		String link = "https://meet.google.com/abc-defg-hij";
		MentorshipRequest request = acceptedRequest();
		Meeting existing = Meeting.forLoggedEntry(
				request, at, 45, "Eski gündem", "Eski not", null, MeetingStatus.SCHEDULED, link, true);
		existing.setId(UUID.randomUUID());
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.of(existing));
		when(meetingRepository.saveAndFlush(existing)).thenReturn(existing);

		MeetingResponse response = meetingService.logMeeting(requestId,
				new LogMeetingRequest(mentorId, null, null, "  Yeni gündem ", null, "Karar alındı", null, null, null));

		assertThat(response.agenda()).isEqualTo("Yeni gündem");
		assertThat(response.outcomes()).isEqualTo("Karar alındı");
		assertThat(response.notes()).isEqualTo("Eski not");
		assertThat(response.scheduledAt()).isEqualTo(at);
		assertThat(response.durationMinutes()).isEqualTo(45);
		assertThat(response.status()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(response.meetLink()).isEqualTo(link);
		assertThat(response.transcribeRequested()).isTrue();
		verify(documentRequestService).copyFromRequestToMeeting(request, existing);
	}

	@Test
	void updateOverwritesTheOutcomesAndLeavesTheNotes() {
		Meeting meeting = Meeting.forLoggedEntry(
				acceptedRequest(), Instant.now(), 30, "Gündem", "Eski not", "Eski sonuç", MeetingStatus.SCHEDULED, null,
				false);
		meeting.setId(UUID.randomUUID());
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(meeting.getId(), null,
				new UpdateMeetingRequest(null, "Yeni sonuç", null, null, null, null));

		assertThat(response.outcomes()).isEqualTo("Yeni sonuç");
		assertThat(response.notes()).isEqualTo("Eski not");
		verify(meetingRepository, never()).saveAll(any());
	}

	@Test
	void updateRefusesToStartRecordingWhenEveryBotIsBusy() {
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		Meeting meeting = heldCandidate(mentorId, at, "https://meet.google.com/abc-defg-hij", MeetingStatus.SCHEDULED);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		recordingBotsBusyAt(at);

		assertThatThrownBy(() -> meetingService.update(meeting.getId(), null,
				new UpdateMeetingRequest(null, null, null, null, null, true))).isInstanceOf(ConflictException.class);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void updateDoesNotLookForAFreeBotAgainForAMeetingAlreadyBeingRecorded() {
		Meeting meeting = heldCandidate(mentorId, Instant.parse("2026-09-10T10:00:00Z"),
				"https://meet.google.com/abc-defg-hij", MeetingStatus.SCHEDULED);
		meeting.setTranscribeRequested(true);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(meeting.getId(), null,
				new UpdateMeetingRequest("Not", null, null, null, null, true));

		assertThat(response.transcribeRequested()).isTrue();
		verify(meetingRepository, never()).findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(any(), any(),
				any());
	}

	@Test
	void updateRecordsTheBotOnEveryMeetingInTheRoomWithoutTouchingTheirStatus() {
		String link = "https://meet.google.com/abc-defg-hij";
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		Instant sentEarlier = at.minus(4, ChronoUnit.MINUTES);
		Meeting meeting = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		Meeting guest = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		Meeting alreadyMarked = heldCandidate(otherMentorId, at, link, MeetingStatus.CANCELLED);
		alreadyMarked.setBotDispatchedAt(sentEarlier);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, at))
				.thenReturn(List.of(meeting, guest, alreadyMarked));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		MeetingResponse response = meetingService.update(meeting.getId(), null,
				new UpdateMeetingRequest(null, null, null, true, null, null));

		assertThat(response.botDispatchedAt()).isNotNull();
		assertThat(guest.getBotDispatchedAt()).isEqualTo(response.botDispatchedAt());
		assertThat(alreadyMarked.getBotDispatchedAt()).isEqualTo(sentEarlier);
		assertThat(guest.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(alreadyMarked.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
		verify(meetingRepository).saveAll(List.of(meeting, guest, alreadyMarked));
	}

	@Test
	void completingAMeetingCompletesItsRoommatesAndGivesThemItsBotTime() {
		String link = "https://meet.google.com/abc-defg-hij";
		Instant at = Instant.parse("2026-09-10T10:00:00Z");
		Instant sentEarlier = at.minus(4, ChronoUnit.MINUTES);
		Meeting meeting = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		meeting.setBotDispatchedAt(sentEarlier);
		Meeting guest = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, at)).thenReturn(List.of(meeting, guest));
		when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

		meetingService.update(meeting.getId(), null,
				new UpdateMeetingRequest(null, null, MeetingStatus.COMPLETED, null, null, null));

		assertThat(guest.getStatus()).isEqualTo(MeetingStatus.COMPLETED);
		assertThat(guest.getBotDispatchedAt()).isEqualTo(sentEarlier);
		assertThat(meeting.getBotDispatchedAt()).isEqualTo(sentEarlier);
	}

	@Test
	void markBotStoppedIgnoresAMissingMeetingId() {
		assertThat(meetingService.markBotStopped(null)).isEmpty();

		verifyNoInteractions(meetingRepository);
	}

	@Test
	void markBotStoppedLeavesAMeetingOnAnotherSiteWhoseLinkOnlyContainsTheCode() {
		Meeting elsewhere = linkedMeeting(Instant.now().minus(15, ChronoUnit.MINUTES), 120);
		elsewhere.setMeetLink("https://example.com/rooms/abc-defg-hij");
		when(meetingRepository.findByStatusAndMeetLinkContainingIgnoreCase(
				MeetingStatus.SCHEDULED, "abc-defg-hij")).thenReturn(List.of(elsewhere));

		assertThat(meetingService.markBotStopped(" abc-defg-hij ")).isEmpty();
		assertThat(elsewhere.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	// Without a length a meeting counts as one hour, plus the hour the dispatcher keeps trying after it.
	@Test
	void markBotStoppedTreatsAMeetingWithoutALengthAsOneHourLong() {
		Meeting stillInItsSlot = linkedMeeting(Instant.now().minus(90, ChronoUnit.MINUTES), 60);
		stillInItsSlot.setDurationMinutes(null);
		Meeting longOver = linkedMeeting(Instant.now().minus(150, ChronoUnit.MINUTES), 60);
		longOver.setDurationMinutes(null);
		when(meetingRepository.findByStatusAndMeetLinkContainingIgnoreCase(
				MeetingStatus.SCHEDULED, "abc-defg-hij")).thenReturn(List.of(stillInItsSlot, longOver));

		assertThat(meetingService.markBotStopped("abc-defg-hij")).containsExactly(stillInItsSlot.getId());
		assertThat(longOver.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	private Meeting feedbackMeeting(AppUser meetingMentor) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(meetingMentor);
		meeting.setOrganization(organization());
		return meeting;
	}

	private AppUser menteeOf(Organization team) {
		AppUser mentee = namedUser(UUID.randomUUID(), "Ece Öğrenci", UserRole.MENTEE);
		mentee.setOrganization(team);
		return mentee;
	}

	private void savingFeedbackAsItIs() {
		when(meetingFeedbackRepository.saveAndFlush(any(MeetingFeedback.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void feedbackFromATeamMemberAlsoRechecksTheMentorsAchievements() {
		Meeting meeting = feedbackMeeting(mentor(mentorId));
		AppUser mentee = menteeOf(organization());
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meeting.getId(), mentee.getId()))
				.thenReturn(Optional.empty());
		savingFeedbackAsItIs();

		var response = meetingService.submitFeedback(meeting.getId(), mentee.getId(),
				new SubmitMeetingFeedbackRequest(List.of(4, 5, 3), List.of()));

		assertThat(response.authorId()).isEqualTo(mentee.getId());
		assertThat(response.createdAt()).isNotNull();
		verify(eventPublisher).publishEvent(new AchievementsMayChangeEvent(Set.of(mentee.getId(), mentorId)));
	}

	@Test
	void feedbackOnAMeetingWithoutAMentorRechecksOnlyTheAuthor() {
		Meeting meeting = feedbackMeeting(null);
		AppUser mentee = menteeOf(organization());
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meeting.getId(), mentee.getId()))
				.thenReturn(Optional.empty());
		savingFeedbackAsItIs();

		meetingService.submitFeedback(meeting.getId(), mentee.getId(),
				new SubmitMeetingFeedbackRequest(List.of(4, 5, 3), List.of()));

		verify(eventPublisher).publishEvent(new AchievementsMayChangeEvent(Set.of(mentee.getId())));
	}

	@Test
	void feedbackFromAMemberOfAnotherStartupIsRefused() {
		Meeting meeting = feedbackMeeting(mentor(mentorId));
		AppUser outsider = menteeOf(namedOrganization(UUID.randomUUID(), "Başka Takım"));
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(outsider.getId())).thenReturn(outsider);

		assertThatThrownBy(() -> meetingService.submitFeedback(meeting.getId(), outsider.getId(),
				new SubmitMeetingFeedbackRequest(List.of(5, 5, 5), List.of())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("participant");
		verify(meetingFeedbackRepository, never()).saveAndFlush(any(MeetingFeedback.class));
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void findFeedbackReturnsWhatTheAuthorLeftAndNothingForSomebodyWhoLeftNone() {
		Meeting meeting = feedbackMeeting(mentor(mentorId));
		MeetingFeedback left = new MeetingFeedback();
		left.setId(UUID.randomUUID());
		left.setMeeting(meeting);
		left.setAuthor(mentor(mentorId));
		left.getRatings().addAll(List.of(5, 4, 3));
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meeting.getId(), mentorId))
				.thenReturn(Optional.of(left));
		when(meetingFeedbackRepository.findByMeetingIdAndAuthorId(meeting.getId(), otherMentorId))
				.thenReturn(Optional.empty());

		assertThat(meetingService.findFeedback(meeting.getId(), mentorId))
				.hasValueSatisfying(feedback -> assertThat(feedback.ratings()).containsExactly(5, 4, 3));
		assertThat(meetingService.findFeedback(meeting.getId(), otherMentorId)).isEmpty();
	}

	@Test
	void confirmHeldRefusesAMeetingThatHasNoMentor() {
		Meeting meeting = heldCandidate(mentorId, Instant.now().minus(1, ChronoUnit.HOURS), null,
				MeetingStatus.SCHEDULED);
		meeting.setMentor(null);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> meetingService.confirmHeld(meeting.getId(), mentorId, true))
				.isInstanceOf(InvalidRequestException.class);
		verify(meetingRepository, never()).saveAll(any());
	}

	@Test
	void confirmHeldKeepsTheTimeASeatWasFirstConfirmed() {
		Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
		Instant confirmedEarlier = at.plus(1, ChronoUnit.HOURS);
		Meeting meeting = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		Meeting confirmedSeat = heldCandidate(mentorId, at, null, MeetingStatus.SCHEDULED);
		confirmedSeat.setHeldConfirmedAt(confirmedEarlier);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, at)).thenReturn(List.of(meeting, confirmedSeat));

		meetingService.confirmHeld(meeting.getId(), mentorId, true);

		assertThat(confirmedSeat.getHeldConfirmedAt()).isEqualTo(confirmedEarlier);
		assertThat(meeting.getHeldConfirmedAt()).isAfter(confirmedEarlier);
	}

	@Test
	void confirmHeldLeavesSeatsOfAnotherMentorOrWithoutOneInTheSameCallAlone() {
		String link = "https://meet.google.com/abc-defg-hij";
		Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
		Meeting meeting = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		Meeting guestMentorSeat = heldCandidate(otherMentorId, at, link, MeetingStatus.SCHEDULED);
		Meeting seatWithoutMentor = heldCandidate(mentorId, at, link, MeetingStatus.SCHEDULED);
		seatWithoutMentor.setMentor(null);
		when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));
		when(meetingRepository.findByMeetLinkAndScheduledAt(link, at))
				.thenReturn(List.of(meeting, guestMentorSeat, seatWithoutMentor));

		meetingService.confirmHeld(meeting.getId(), mentorId, true);

		assertThat(meeting.getHeldConfirmedAt()).isNotNull();
		assertThat(guestMentorSeat.getHeldConfirmedAt()).isNull();
		assertThat(seatWithoutMentor.getHeldConfirmedAt()).isNull();
		verify(meetingRepository).saveAll(List.of(meeting));
	}
}
