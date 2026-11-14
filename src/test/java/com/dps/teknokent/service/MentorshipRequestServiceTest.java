package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipWorkspaceTab;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.PostMentorMessageRequest;
import com.dps.teknokent.dto.requests.RecommendationEvidenceSubmission;
import com.dps.teknokent.dto.requests.RecommendationItemSubmission;
import com.dps.teknokent.dto.requests.RecommendationSnapshotSubmission;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.requests.UpdatePlannedSessionsRequest;
import com.dps.teknokent.dto.requests.WithdrawMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorshipRequestMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRecommendationRunRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.MentorshipRequestWorkspaceQuery;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

@ExtendWith(MockitoExtension.class)
class MentorshipRequestServiceTest {

	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;

	@Mock
	private MentorshipRequestWorkspaceQuery mentorshipRequestWorkspaceQuery;

	@Mock
	private MentorshipRequestRecommendationRunRepository recommendationRunRepository;

	@Mock
	private MentorshipRequestMessageRepository mentorshipRequestMessageRepository;

	@Mock
	private MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;

	@Mock
	private MeetingRepository meetingRepository;

	@Mock
	private DomainLookupService domainLookupService;

	@Mock
	private MentorProfileService mentorProfileService;

	@Mock
	private NotificationService notificationService;

	@Mock
	private MailService mailService;

	@Mock
	private DocumentRequestService documentRequestService;

	@Mock
	private MenteeProgramService menteeProgramService;

	@Mock
	private ProgramRepository programRepository;

	@InjectMocks
	private MentorshipRequestService mentorshipRequestService;

	@Test
	void create_refusesASelfRequestBeforeLookingUpEitherSide() {
		UUID actorId = UUID.randomUUID();
		assertThatThrownBy(() -> mentorshipRequestService.create(
				submission(actorId, UUID.randomUUID(), actorId)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.selfMentorshipRequest"));
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void findAll_returnsMappedRequestsInDescendingCreationOrder() {
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");

		MentorshipRequest first = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000401"),
				requester, organization, mentor, "Roadmap help", MentorshipRequestStatus.PENDING);
		MentorshipRequest second = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000402"),
				requester, organization, mentor, "Hiring strategy", MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId))
				.thenReturn(List.of(first, second));

		var result = mentorshipRequestService.findAll(requesterId, null, null);

		assertThat(result).hasSize(2);
		assertThat(result.getFirst().requesterId()).isEqualTo(requesterId);
		assertThat(result.get(1).status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
	}

	@Test
	void findAll_listsEveryRequestForTheStartup() {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
				"requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser teammate = user(UUID.fromString("00000000-0000-0000-0000-000000000102"),
				"teammate@tekno.com", "Mert Kaya", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(organizationId, "Teknokent");

		MentorshipRequest first = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000401"),
				requester, organization, mentor, "Roadmap help", MentorshipRequestStatus.PENDING);
		MentorshipRequest second = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000402"),
				teammate, organization, mentor, "Hiring strategy", MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId))
				.thenReturn(List.of(first, second));

		var result = mentorshipRequestService.findAll(null, null, organizationId);

		assertThat(result).hasSize(2);
		assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
		assertThat(result.get(1).requesterId()).isEqualTo(teammate.getId());
	}

	// A session without a startup sends a blank organization, which arrives here as null.
	@Test
	void findAll_withoutAScope_refusesInsteadOfListingEveryRequest() {
		assertThatThrownBy(() -> mentorshipRequestService.findAll(null, null, null))
				.isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(mentorshipRequestRepository);
	}

	@Test
	void findWorkspacePage_returnsOnlyRequestedPageOfMatchingTab() {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID pendingId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
				"requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(organizationId, "Teknokent");
		MentorshipRequest pending = mentorshipRequest(
				pendingId, requester, organization, mentor, "Roadmap", MentorshipRequestStatus.PENDING);

		when(mentorshipRequestWorkspaceQuery.findPage(
				eq(null),
				eq(organizationId),
				eq(MentorshipWorkspaceTab.REQUESTS),
				any(),
				any()))
				.thenReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 1), 2));

		var result = mentorshipRequestService.findWorkspacePage(
				null, organizationId, MentorshipWorkspaceTab.REQUESTS, PageRequest.of(0, 1));

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().getFirst().id()).isEqualTo(pendingId);
		assertThat(result.totalElements()).isEqualTo(2);
		assertThat(result.totalPages()).isEqualTo(2);
		assertThat(result.page()).isZero();
		assertThat(result.size()).isEqualTo(1);
	}

	@Test
	void create_requiresRequesterToBeMenteeAndMentorToBeMentor() {
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");

		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTOR);

		when(domainLookupService.requireUser(requesterId)).thenReturn(requester);

		CreateMentorshipRequestSubmission request = new CreateMentorshipRequestSubmission(
				requesterId,
				organizationId,
				mentorId,
				"Arda Demir",
				"Product strategy",
				"We need help validating our roadmap.",
				MentorshipRequestUrgency.THIS_WEEK,
				MentorshipRequestFormat.SINGLE_MEETING,
				MeetingMode.ONLINE,
				"Need guidance",
				null, null);

		assertThatThrownBy(() -> mentorshipRequestService.create(request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTEE role");
	}

	@Test
	void create_savesRequestAndMapsResponseWhenInputsAreValid() {
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");

		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(organizationId, "Teknokent");

		when(domainLookupService.requireUser(requesterId)).thenReturn(requester);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
		when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class))).thenAnswer(invocation -> {
			MentorshipRequest saved = invocation.getArgument(0);
			saved.setId(requestId);
			saved.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
			saved.setUpdatedAt(Instant.parse("2026-08-21T08:00:00Z"));
			return saved;
		});
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		CreateMentorshipRequestSubmission request = new CreateMentorshipRequestSubmission(
				requesterId,
				organizationId,
				mentorId,
				"Arda Demir",
				"Product strategy",
				"We need help validating our roadmap.",
				MentorshipRequestUrgency.THIS_WEEK,
				MentorshipRequestFormat.SINGLE_MEETING,
				MeetingMode.ONLINE,
				"Need guidance",
				null, null);

		var result = mentorshipRequestService.create(request);

		assertThat(result.id()).isEqualTo(requestId);
		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(result.mentorId()).isEqualTo(mentorId);
		assertThat(result.summary()).isEqualTo("We need help validating our roadmap.");
		verify(mailService).sendAfterCommit(
				eq("mentor@tekno.com"),
				eq("Yeni mentorluk talebi"),
				argThat((String body) -> body.contains("Ayşe Demir")
						&& body.contains("Product strategy")));
	}

	@Test
	void decide_acceptsRequestWhenAssignedMentorConfirms() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.decide(requestId,
				new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.ACCEPTED, "Let's meet",
						null));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(result.mentorMessage()).isEqualTo("Let's meet");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
	}

	@Test
	void decide_acceptWithoutATimeBooksTheFirstOpenTimeTheMenteePicked() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);
		request.setRequesterMessage("Tercih edilen saatler: …\n"
				+ "preferredSlots:2020-01-01T10:00,2099-01-05T10:00,2099-01-06T14:00");
		Instant taken = MeetingSlotTimes.parseMachineSlot("2099-01-05T10:00");
		Instant open = MeetingSlotTimes.parseMachineSlot("2099-01-06T14:00");

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, taken, MeetingStatus.SCHEDULED))
				.thenReturn(true);
		when(meetingRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		mentorshipRequestService.decide(requestId,
				new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.ACCEPTED,
						"Talebiniz kabul edildi.", null));

		verify(meetingRepository).saveAndFlush(argThat((Meeting meeting) -> open.equals(meeting.getScheduledAt())
				&& meeting.getStatus() == MeetingStatus.SCHEDULED));
	}

	@Test
	void decide_counterProposesAlternativeSlots() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);
		String message = "Alternatif öneriyorum.\nproposedSlots:2099-09-12T15:00,2099-09-13T10:30";

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.decide(requestId,
				new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.COUNTER_PROPOSED,
						message, null));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
		assertThat(request.getMentorMessage()).contains("proposedSlots:2099-09-12T15:00");
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage saved) -> saved
						.getMessageType() == MentorshipRequestMessageType.MENTOR_MESSAGE));
	}

	@Test
	void decide_moreInfoSavesTheRequestedDocuments() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		mentorshipRequestService.decide(requestId,
				new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.MORE_INFO_REQUESTED,
						"Gelir modelinizi paylaşır mısınız?", List.of("Pitch deck")));

		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.MORE_INFO_REQUESTED);
		verify(documentRequestService).saveTitlesOnRequest(request, mentor, List.of("Pitch deck"));
	}

	@Test
	void decide_refusesAProposedTimeSoonerThanFourHours() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);
		String inAnHour = java.time.LocalDateTime.now(MeetingSlotTimes.APP_ZONE).plusHours(1)
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);

		assertThatThrownBy(() -> mentorshipRequestService.decide(requestId,
				new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.COUNTER_PROPOSED,
						"Alternatif.\nproposedSlots:" + inAnHour, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Görüşme saati en erken 4 saat sonrası için seçilebilir.");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
	}

	@Test
	void acceptProposedSlot_acceptsWhenMenteePicksAProposedTime() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.COUNTER_PROPOSED);
		request.setMentorMessage("proposedSlots:2026-09-12T15:00,2026-09-13T10:30");

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.findByMentorshipRequestId(requestId)).thenReturn(Optional.empty());
		when(meetingRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var result = mentorshipRequestService.acceptProposedSlot(requestId,
				new AcceptProposedSlotRequest(requesterId, "2026-09-12T15:00"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(request.getMentorMessage()).isEqualTo("Görüşme saati: 2026-09-12T15:00");
		verify(meetingRepository).saveAndFlush(any());
	}

	@Test
	void reply_savesMenteeReplyAndMarksRepliedWhenMoreInfoRequested() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.MORE_INFO_REQUESTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.reply(requestId,
				new ReplyToMentorshipRequest(requesterId, "Bütçemiz 50.000 TL"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTEE_REPLY
						&& message.getBody().equals("Bütçemiz 50.000 TL")));
		verify(notificationService).create(
				eq(mentor),
				eq(NotificationCategory.MESSAGE),
				eq("Ek bilgi talebinize yanıt geldi"),
				eq("Ayşe Demir yeni bir mesaj gönderdi."),
				eq("/mentor-dashboard"));
	}

	@Test
	void reply_savesMenteeMessageWithoutChangingStatusWhenAccepted() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.reply(requestId,
				new ReplyToMentorshipRequest(requesterId, "Salı uygun mu?"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTEE_MESSAGE
						&& message.getBody().equals("Salı uygun mu?")));
		verify(notificationService).create(
				eq(mentor),
				eq(NotificationCategory.MESSAGE),
				eq("Startup'tan yeni mesaj"),
				eq("Ayşe Demir yeni bir mesaj gönderdi."),
				eq("/mentor-dashboard"));
	}

	@Test
	void reply_savesMenteeMessageWhenPending() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.PENDING);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.reply(requestId,
				new ReplyToMentorshipRequest(requesterId, "Merhaba"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTEE_MESSAGE
						&& message.getBody().equals("Merhaba")));
	}

	@Test
	void postMentorMessage_savesMentorMessageWhenAccepted() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.postMentorMessage(requestId,
				new PostMentorMessageRequest(mentorId, "Yarın 14:00 uygun mu?"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTOR_MESSAGE
						&& message.getBody().equals("Yarın 14:00 uygun mu?")));
		assertThat(request.getMentorReadAt()).isNotNull();
		verify(notificationService).create(
				eq(requester),
				eq(NotificationCategory.MESSAGE),
				eq("Mentorunuzdan yeni mesaj"),
				eq("Dr. Kemal Arslan yeni bir mesaj gönderdi."),
				eq("/mentor-request/status?request=" + requestId));
	}

	@Test
	void postMentorMessage_rejectsWhenMentorIsNotAssignedToRequest() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID otherMentorId = UUID.fromString("00000000-0000-0000-0000-000000000999");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.postMentorMessage(requestId,
				new PostMentorMessageRequest(otherMentorId, "Merhaba")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("assigned mentor");
	}

	@Test
	void updatePlannedSessions_storesTheCountForTheAssignedMentor() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000402");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.updatePlannedSessions(requestId,
				new UpdatePlannedSessionsRequest(mentorId, 6));

		assertThat(result.plannedSessionCount()).isEqualTo(6);
		assertThat(request.getPlannedSessionCount()).isEqualTo(6);
	}

	@Test
	void updatePlannedSessions_rejectsAnotherMentor() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000403");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.ACCEPTED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.updatePlannedSessions(requestId,
				new UpdatePlannedSessionsRequest(
						UUID.fromString("00000000-0000-0000-0000-000000000999"), 6)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("assigned mentor");
		assertThat(request.getPlannedSessionCount()).isNull();
	}

	@Test
	void updatePlannedSessions_rejectsARequestThatIsNotAccepted() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000404");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.PENDING);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.updatePlannedSessions(requestId,
				new UpdatePlannedSessionsRequest(mentorId, 6)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("accepted");
	}

	@Test
	void postMentorMessage_savesMentorMessageWhenPending() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.PENDING);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.postMentorMessage(requestId,
				new PostMentorMessageRequest(mentorId, "Merhaba"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTOR_MESSAGE
						&& message.getBody().equals("Merhaba")));
	}

	@Test
	void postMentorMessage_rejectsWhenRequestIsDeclined() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
				"Ayşe Demir", UserRole.MENTEE);
		AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help",
				MentorshipRequestStatus.DECLINED);

		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.postMentorMessage(requestId,
				new PostMentorMessageRequest(mentorId, "Merhaba")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("open mentorship request");
	}

	@Test
	void markRead_stampsTheMentorsSideWhenTheMentorOpensTheThread() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
				"requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		requester.setOrganization(organization);
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.ACCEPTED);
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);

		var result = mentorshipRequestService.markRead(requestId, mentor.getId());

		assertThat(result.mentorReadAt()).isNotNull();
		assertThat(result.menteeReadAt()).isNull();
	}

	@Test
	void markRead_letsAnyTeammateMarkTheStartupsSideRead() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
				"requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser teammate = user(UUID.fromString("00000000-0000-0000-0000-000000000102"),
				"teammate@tekno.com", "Can Yılmaz", UserRole.MENTEE);
		requester.setOrganization(organization);
		teammate.setOrganization(organization);
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.ACCEPTED);
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(teammate.getId())).thenReturn(teammate);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);

		var result = mentorshipRequestService.markRead(requestId, teammate.getId());

		assertThat(result.menteeReadAt()).isNotNull();
		assertThat(result.mentorReadAt()).isNull();
	}

	@Test
	void markRead_rejectsSomeoneOutsideTheConversation() {
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
				"Dr. Kemal Arslan", UserRole.MENTOR);
		Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
				"Teknokent");
		AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
				"requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
		AppUser outsider = user(UUID.fromString("00000000-0000-0000-0000-000000000999"),
				"outsider@tekno.com", "Başka Biri", UserRole.MENTEE);
		outsider.setOrganization(organization(UUID.fromString("00000000-0000-0000-0000-000000000399"),
				"Başka Startup"));
		MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
				"Roadmap help", MentorshipRequestStatus.ACCEPTED);
		when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(outsider.getId())).thenReturn(outsider);

		assertThatThrownBy(() -> mentorshipRequestService.markRead(requestId, outsider.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not part of this conversation");
		assertThat(request.getMenteeReadAt()).isNull();
	}

	// --- Withdrawing, the availability list and booking: the steps after a mentor answers ---

	private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID REQUESTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");

	private static MentorshipRequest requestIn(MentorshipRequestStatus status) {
		return mentorshipRequest(REQUEST_ID,
				user(REQUESTER_ID, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE),
				organization(UUID.fromString("00000000-0000-0000-0000-000000000904"), "GreenLoop"),
				user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR),
				"Roadmap help", status);
	}

	private static MentorAvailabilitySlot slot(String id, AppUser mentor, Instant startsAt) {
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(UUID.fromString(id));
		slot.setMentor(mentor);
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(45);
		return slot;
	}

	// Building the response reads only some of these, depending on the request's status.
	private void stubResponseLookups() {
		lenient().when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(REQUEST_ID))
				.thenReturn(List.of());
		lenient().when(recommendationRunRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		lenient().when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.empty());
	}

	@Test
	void withdraw_letsTheRequesterTakeBackAnUnansweredRequest() {
		for (MentorshipRequestStatus status : List.of(MentorshipRequestStatus.PENDING,
				MentorshipRequestStatus.MORE_INFO_REQUESTED, MentorshipRequestStatus.COUNTER_PROPOSED)) {
			MentorshipRequest request = requestIn(status);
			when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
			when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
			stubResponseLookups();

			var result = mentorshipRequestService.withdraw(REQUEST_ID,
					new WithdrawMentorshipRequest(REQUESTER_ID));

			assertThat(result.status()).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		}
	}

	@Test
	void withdraw_rejectsAnyoneButTheRequester() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.withdraw(REQUEST_ID,
				new WithdrawMentorshipRequest(MENTOR_ID)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the requester");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
	}

	@Test
	void withdraw_rejectsARequestTheMentorAlreadyAnswered() {
		for (MentorshipRequestStatus status : List.of(MentorshipRequestStatus.ACCEPTED,
				MentorshipRequestStatus.DECLINED, MentorshipRequestStatus.WITHDRAWN)) {
			MentorshipRequest request = requestIn(status);
			when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

			assertThatThrownBy(() -> mentorshipRequestService.withdraw(REQUEST_ID,
					new WithdrawMentorshipRequest(REQUESTER_ID)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("unanswered");
			assertThat(request.getStatus()).isEqualTo(status);
		}
	}

	@Test
	void findAvailability_isClosedUntilTheMentorAccepts() {
		when(mentorshipRequestRepository.findById(REQUEST_ID))
				.thenReturn(Optional.of(requestIn(MentorshipRequestStatus.PENDING)));

		assertThatThrownBy(() -> mentorshipRequestService.findAvailability(REQUEST_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("after the request is accepted");
	}

	@Test
	void findAvailability_leavesOutTimesTheMentorHasAlreadyBooked() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		Instant free = Instant.now().plus(Duration.ofDays(2));
		Instant taken = Instant.now().plus(Duration.ofDays(3));
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(eq(MENTOR_ID),
				any())).thenReturn(List.of(
						slot("00000000-0000-0000-0000-000000000911", request.getMentor(), free),
						slot("00000000-0000-0000-0000-000000000912", request.getMentor(), taken)));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(MENTOR_ID, free, MeetingStatus.SCHEDULED))
				.thenReturn(false);
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(MENTOR_ID, taken, MeetingStatus.SCHEDULED))
				.thenReturn(true);

		var slots = mentorshipRequestService.findAvailability(REQUEST_ID);

		assertThat(slots).extracting(item -> item.startsAt()).containsExactly(free);
	}

	@Test
	void scheduleMeeting_booksTheChosenSlotAndTellsTheMentor() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		Instant startsAt = Instant.now().plus(Duration.ofDays(2));
		MentorAvailabilitySlot chosen = slot("00000000-0000-0000-0000-000000000911", request.getMentor(),
				startsAt);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		when(mentorAvailabilitySlotRepository.findById(chosen.getId())).thenReturn(Optional.of(chosen));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(MENTOR_ID, startsAt,
				MeetingStatus.SCHEDULED)).thenReturn(false);
		when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(call -> call.getArgument(0));

		var meeting = mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, chosen.getId()));

		assertThat(meeting.scheduledAt()).isEqualTo(startsAt);
		assertThat(meeting.durationMinutes()).isEqualTo(45);
		assertThat(meeting.status()).isEqualTo(MeetingStatus.SCHEDULED);
		verify(notificationService).create(eq(request.getMentor()), eq(NotificationCategory.MEETING_CHANGE),
				any(), any(), eq("/mentor-dashboard/calendar"));
	}

	@Test
	void scheduleMeeting_refusesAnyoneButTheRequesterOrBeforeAcceptance() {
		when(mentorshipRequestRepository.findById(REQUEST_ID))
				.thenReturn(Optional.of(requestIn(MentorshipRequestStatus.ACCEPTED)));
		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(MENTOR_ID, UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the requester");

		when(mentorshipRequestRepository.findById(REQUEST_ID))
				.thenReturn(Optional.of(requestIn(MentorshipRequestStatus.PENDING)));
		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("after the request is accepted");
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void scheduleMeeting_refusesASecondMeetingForTheSameRequest() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.of(
				Meeting.forMentorshipRequest(request, slot("00000000-0000-0000-0000-000000000911",
						request.getMentor(), Instant.now().plus(Duration.ofDays(1))))));

		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("already been scheduled");
	}

	@Test
	void scheduleMeeting_refusesAnotherMentorsSlot() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		AppUser otherMentor = user(UUID.fromString("00000000-0000-0000-0000-000000000920"), "x@mentor.com",
				"Another Mentor", UserRole.MENTOR);
		MentorAvailabilitySlot foreign = slot("00000000-0000-0000-0000-000000000911", otherMentor,
				Instant.now().plus(Duration.ofDays(2)));
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		when(mentorAvailabilitySlotRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, foreign.getId())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not available for this request");
	}

	// Mentors are promised at least the booking lead time before a meeting starts.
	@Test
	void scheduleMeeting_refusesASlotThatStartsTooSoon() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		MentorAvailabilitySlot soon = slot("00000000-0000-0000-0000-000000000911", request.getMentor(),
				Instant.now().plus(Duration.ofMinutes(30)));
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		when(mentorAvailabilitySlotRepository.findById(soon.getId())).thenReturn(Optional.of(soon));

		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, soon.getId())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.slotTooSoon"));
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void scheduleMeeting_refusesASlotSomeoneElseJustBooked() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		Instant startsAt = Instant.now().plus(Duration.ofDays(2));
		MentorAvailabilitySlot taken = slot("00000000-0000-0000-0000-000000000911", request.getMentor(),
				startsAt);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		when(mentorAvailabilitySlotRepository.findById(taken.getId())).thenReturn(Optional.of(taken));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(MENTOR_ID, startsAt,
				MeetingStatus.SCHEDULED)).thenReturn(true);

		assertThatThrownBy(() -> mentorshipRequestService.scheduleMeeting(REQUEST_ID,
				new ScheduleMentorshipRequest(REQUESTER_ID, taken.getId())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("just been booked");
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	private static MentorshipRequest mentorshipRequest(UUID id, AppUser requester, Organization organization,
			AppUser mentor,
			String summary, MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(id);
		request.setRequester(requester);
		request.setOrganization(organization);
		request.setMentor(mentor);
		request.setRequestedForName("Arda Demir");
		request.setSupportArea("Product strategy");
		request.setSummary(summary);
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(status);
		request.setRequesterMessage("Need guidance");
		request.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
		request.setUpdatedAt(Instant.parse("2026-08-21T08:01:00Z"));
		return request;
	}

	private static Organization organization(UUID id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}

	private static AppUser user(UUID id, String email, String fullName, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setEmail(email);
		user.setFullName(fullName);
		user.setRole(role);
		return user;
	}

	@Test
	void create_refusesAMentorOutsideTheMenteesProgram() {
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID programId = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
		Organization organization = organization(organizationId, "Teknokent");
		when(domainLookupService.requireUser(requesterId))
				.thenReturn(user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE));
		when(domainLookupService.requireUser(mentorId))
				.thenReturn(user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR));
		when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
		when(menteeProgramService.programOf(organization)).thenReturn(Optional.of(programId));
		when(menteeProgramService.acceptedMentorIds(programId)).thenReturn(Set.of(UUID.randomUUID()));

		assertThatThrownBy(() -> mentorshipRequestService.create(
				submission(requesterId, organizationId, mentorId)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentor havuzunda değil");
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void create_recordsTheMenteesProgramOnTheRequest() {
		UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		Program program = new Program();
		program.setId(UUID.fromString("00000000-0000-0000-0000-0000000005a1"));
		program.setName("METU BİGG 2026");
		Organization organization = organization(organizationId, "Teknokent");
		when(domainLookupService.requireUser(requesterId))
				.thenReturn(user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE));
		when(domainLookupService.requireUser(mentorId))
				.thenReturn(user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR));
		when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
		when(menteeProgramService.programOf(organization)).thenReturn(Optional.of(program.getId()));
		when(menteeProgramService.acceptedMentorIds(program.getId())).thenReturn(Set.of(mentorId));
		when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
		when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class))).thenAnswer(invocation -> {
			MentorshipRequest saved = invocation.getArgument(0);
			saved.setId(requestId);
			return saved;
		});
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.create(submission(requesterId, organizationId, mentorId));

		assertThat(result.programId()).isEqualTo(program.getId());
		assertThat(result.programName()).isEqualTo("METU BİGG 2026");
	}

	private static CreateMentorshipRequestSubmission submission(UUID requesterId, UUID organizationId,
			UUID mentorId) {
		return new CreateMentorshipRequestSubmission(requesterId, organizationId, mentorId, "Arda Demir",
				"Product strategy", "We need help validating our roadmap.",
				MentorshipRequestUrgency.THIS_WEEK, MentorshipRequestFormat.SINGLE_MEETING,
				MeetingMode.ONLINE, "Need guidance", null, null);
	}

	// --- Lists and single lookups ---

	@Test
	void findAll_listsEveryRequestSentToAMentor() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(MENTOR_ID))
				.thenReturn(List.of(request));

		var result = mentorshipRequestService.findAll(null, MENTOR_ID, null);

		assertThat(result).extracting(item -> item.id()).containsExactly(REQUEST_ID);
		assertThat(result.getFirst().mentorId()).isEqualTo(MENTOR_ID);
	}

	@Test
	void findAll_anEmptyListAsksForNoMessagesOrSnapshots() {
		when(mentorshipRequestRepository.findByMentorIdOrderByCreatedAtDesc(MENTOR_ID)).thenReturn(List.of());

		assertThat(mentorshipRequestService.findAll(null, MENTOR_ID, null)).isEmpty();

		verifyNoInteractions(mentorshipRequestMessageRepository, recommendationRunRepository);
	}

	@Test
	void findWorkspacePage_asksForNewestFirstUnlessTheCallerSendsASort() {
		when(mentorshipRequestWorkspaceQuery.findPage(any(), any(), any(), any(), any()))
				.thenAnswer(call -> Page.empty(call.getArgument(3)));

		mentorshipRequestService.findWorkspacePage(MENTOR_ID, null, MentorshipWorkspaceTab.ACTIVE,
				PageRequest.of(2, 5));
		mentorshipRequestService.findWorkspacePage(MENTOR_ID, null, MentorshipWorkspaceTab.ACTIVE,
				PageRequest.of(0, 5, Sort.by("updatedAt")));

		ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
		verify(mentorshipRequestWorkspaceQuery, times(2)).findPage(eq(MENTOR_ID), isNull(),
				eq(MentorshipWorkspaceTab.ACTIVE), asked.capture(), any());
		assertThat(asked.getAllValues().getFirst())
				.isEqualTo(PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "createdAt")));
		assertThat(asked.getAllValues().get(1).getSort()).isEqualTo(Sort.by("updatedAt"));
	}

	@Test
	void findById_returnsTheRequestWithItsConversation() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.MORE_INFO_REQUESTED);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(REQUEST_ID))
				.thenReturn(List.of(MentorshipRequestMessage.create(request, request.getMentor(),
						MentorshipRequestMessageType.MENTOR_QUESTION, "Gelir modeliniz nedir?")));
		when(recommendationRunRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());

		var result = mentorshipRequestService.findById(REQUEST_ID);

		assertThat(result.id()).isEqualTo(REQUEST_ID);
		assertThat(result.messages()).extracting(message -> message.body())
				.containsExactly("Gelir modeliniz nedir?");
		assertThat(result.recommendationSnapshot()).isNull();
	}

	@Test
	void findById_reportsARequestThatDoesNotExist() {
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorshipRequestService.findById(REQUEST_ID))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(REQUEST_ID.toString());
	}

	@Test
	void findAlternatives_offersEveryOtherMentorToAStartupOutsideAnyProgram() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.DECLINED);
		MentorProfileResponse other = mentorProfile(UUID.fromString("00000000-0000-0000-0000-000000000931"),
				"Selin Aksoy");
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(mentorProfileService.findAllExcept(MENTOR_ID)).thenReturn(List.of(other));

		assertThat(mentorshipRequestService.findAlternatives(REQUEST_ID)).containsExactly(other);
		verifyNoInteractions(menteeProgramService);
	}

	@Test
	void findAlternatives_staysInsideTheProgramsMentorPool() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.DECLINED);
		Program program = new Program();
		program.setId(UUID.fromString("00000000-0000-0000-0000-0000000009a1"));
		request.setProgram(program);
		UUID poolMentorId = UUID.fromString("00000000-0000-0000-0000-000000000931");
		MentorProfileResponse inPool = mentorProfile(poolMentorId, "Selin Aksoy");
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(menteeProgramService.acceptedMentorIds(program.getId())).thenReturn(Set.of(MENTOR_ID, poolMentorId));
		when(mentorProfileService.findAmongExcept(Set.of(MENTOR_ID, poolMentorId), MENTOR_ID))
				.thenReturn(List.of(inPool));

		assertThat(mentorshipRequestService.findAlternatives(REQUEST_ID)).containsExactly(inPool);
		verify(mentorProfileService, never()).findAllExcept(any());
	}

	// --- Creating: who may ask whom, and the recommendation the mentee chose from ---

	private static final UUID NEW_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000941");
	private static final UUID ORGANIZATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000904");
	private static final UUID OTHER_MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000931");

	private static CreateMentorshipRequestSubmission submissionWith(String requesterMessage,
			RecommendationSnapshotSubmission snapshot) {
		return new CreateMentorshipRequestSubmission(REQUESTER_ID, ORGANIZATION_ID, MENTOR_ID, "Arda Demir",
				"Product strategy", "We need help validating our roadmap.", MentorshipRequestUrgency.THIS_WEEK,
				MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE, requesterMessage, snapshot, null);
	}

	private static MentorProfileResponse mentorProfile(UUID id, String fullName, MentorEvidenceResponse... evidence) {
		return new MentorProfileResponse(id, fullName, "mentor@tekno.com", "KA", "CTO", "Acme", "", "", "", "", "",
				"", "", "", "", List.of(), List.of(), List.of(evidence));
	}

	private static RecommendationItemSubmission recommended(UUID mentorId, int rank, boolean selected,
			RecommendationEvidenceSubmission... evidence) {
		return new RecommendationItemSubmission(mentorId, rank, selected, "Name the client sent",
				"Title the client sent", "Company the client sent", List.of(" Fintech deneyimi ", "Fintech deneyimi"),
				List.of(evidence));
	}

	private static RecommendationSnapshotSubmission snapshot(RecommendationItemSubmission... recommendations) {
		return new RecommendationSnapshotSubmission("hybrid", "matcher-v2", Instant.parse("2026-08-20T09:00:00Z"),
				"GreenLoop", "Enerji", "MVP", "Akıllı sayaçlar", List.of("IoT"), List.of(recommendations));
	}

	// Everything create() reads before it looks at the recommendation snapshot.
	private AppUser stubCreationUpToTheSave() {
		AppUser mentor = user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR);
		when(domainLookupService.requireUser(REQUESTER_ID))
				.thenReturn(user(REQUESTER_ID, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE));
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
		when(domainLookupService.requireOrganization(ORGANIZATION_ID))
				.thenReturn(organization(ORGANIZATION_ID, "GreenLoop"));
		when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class))).thenAnswer(invocation -> {
			MentorshipRequest saved = invocation.getArgument(0);
			saved.setId(NEW_REQUEST_ID);
			return saved;
		});
		return mentor;
	}

	private void assertSnapshotIsRefused(RecommendationSnapshotSubmission snapshot, String reason) {
		assertThatThrownBy(() -> mentorshipRequestService.create(submissionWith("Need guidance", snapshot)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining(reason);
		verify(recommendationRunRepository, never()).saveAndFlush(any());
		verifyNoInteractions(notificationService, mailService);
	}

	@Test
	void create_refusesSomeoneWhoIsNotAMentor() {
		when(domainLookupService.requireUser(REQUESTER_ID))
				.thenReturn(user(REQUESTER_ID, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE));
		when(domainLookupService.requireUser(MENTOR_ID))
				.thenReturn(user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.COORDINATOR));

		assertThatThrownBy(() -> mentorshipRequestService.create(submissionWith("Need guidance", null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("MENTOR role");
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void create_refusesAPreferredTimeInsideTheBookingLeadTime() {
		String inAnHour = MeetingSlotTimes.formatMachineSlot(Instant.now().plus(Duration.ofHours(1)));
		when(domainLookupService.requireUser(REQUESTER_ID))
				.thenReturn(user(REQUESTER_ID, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE));
		when(domainLookupService.requireUser(MENTOR_ID))
				.thenReturn(user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR));

		assertThatThrownBy(() -> mentorshipRequestService.create(
				submissionWith("Tercih edilen saatler\npreferredSlots:2099-01-05T10:00," + inAnHour, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.slotTooSoon"));
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void create_refusesAStartupThatIsNotApproved() {
		for (UserAccountStatus status : List.of(UserAccountStatus.PENDING, UserAccountStatus.REJECTED)) {
			Organization waiting = organization(ORGANIZATION_ID, "GreenLoop");
			waiting.setApprovalStatus(status);
			when(domainLookupService.requireUser(REQUESTER_ID))
					.thenReturn(user(REQUESTER_ID, "ayse@startup.com", "Ayşe Demir", UserRole.MENTEE));
			when(domainLookupService.requireUser(MENTOR_ID))
					.thenReturn(user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTOR));
			when(domainLookupService.requireOrganization(ORGANIZATION_ID)).thenReturn(waiting);

			assertThatThrownBy(() -> mentorshipRequestService.create(submissionWith("Need guidance", null)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessage(Texts.forRequest("error.startupNotApproved"));
		}
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void create_tellsTheMentorInAppAboutTheNewRequest() {
		AppUser mentor = stubCreationUpToTheSave();
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(NEW_REQUEST_ID))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(NEW_REQUEST_ID)).thenReturn(Optional.empty());

		mentorshipRequestService.create(submissionWith("Need guidance", null));

		verify(notificationService).create(eq(mentor), eq(Texts.get(mentor, "notification.newRequest.title")),
				argThat((String body) -> body.contains("Ayşe Demir") && body.contains("Product strategy")),
				eq("/mentor-dashboard"));
		verify(recommendationRunRepository, never()).saveAndFlush(any());
	}

	// The names and evidence kept with a request are the mentor's own, whatever the client put in the snapshot.
	@Test
	void create_keepsTheRecommendationSnapshotWithTheMentorsOwnProfileDetails() {
		stubCreationUpToTheSave();
		MentorEvidenceResponse confirmed = new MentorEvidenceResponse("sector", "Fintech'te 8 yıl",
				"8 yıl ödeme sistemleri", 2, "v3");
		AppUser otherMentor = user(OTHER_MENTOR_ID, "selin@mentor.com", "Selin Aksoy", UserRole.MENTOR);
		when(mentorProfileService.findAll()).thenReturn(List.of(
				mentorProfile(MENTOR_ID, "Dr. Kemal Arslan", confirmed),
				mentorProfile(OTHER_MENTOR_ID, "Selin Aksoy")));
		when(domainLookupService.requireUser(OTHER_MENTOR_ID)).thenReturn(otherMentor);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(NEW_REQUEST_ID))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(NEW_REQUEST_ID)).thenReturn(Optional.empty());

		mentorshipRequestService.create(submissionWith("Need guidance", snapshot(
				recommended(MENTOR_ID, 1, true, new RecommendationEvidenceSubmission(" sector ",
						" Fintech'te 8 yıl", "8 yıl ödeme sistemleri ", 2, "v3")),
				recommended(OTHER_MENTOR_ID, 2, false))));

		ArgumentCaptor<MentorshipRequestRecommendationRun> saved = ArgumentCaptor
				.forClass(MentorshipRequestRecommendationRun.class);
		verify(recommendationRunRepository).saveAndFlush(saved.capture());
		MentorshipRequestRecommendationRun run = saved.getValue();
		assertThat(run.getRequest().getId()).isEqualTo(NEW_REQUEST_ID);
		assertThat(run.getMatchingMethod()).isEqualTo("hybrid");
		assertThat(run.getRecommendations()).hasSize(2);
		MentorshipRequestRecommendationItem chosen = run.getRecommendations().getFirst();
		assertThat(chosen.isSelected()).isTrue();
		assertThat(chosen.getMentor().getId()).isEqualTo(MENTOR_ID);
		assertThat(chosen.getMentorName()).isEqualTo("Dr. Kemal Arslan");
		assertThat(chosen.getProfessionalTitle()).isEqualTo("CTO");
		assertThat(chosen.getCompany()).isEqualTo("Acme");
		assertThat(chosen.getReasons()).containsExactly("Fintech deneyimi");
		assertThat(chosen.getEvidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.getClaim()).isEqualTo("Fintech'te 8 yıl");
			assertThat(evidence.getQuote()).isEqualTo("8 yıl ödeme sistemleri");
			assertThat(evidence.getSourcePage()).isEqualTo(2);
		});
		MentorshipRequestRecommendationItem alternative = run.getRecommendations().get(1);
		assertThat(alternative.isSelected()).isFalse();
		assertThat(alternative.getMentor()).isSameAs(otherMentor);
		assertThat(alternative.getEvidence()).isEmpty();
	}

	@Test
	void create_refusesASnapshotWhoseChosenMentorIsNotTheOneRequested() {
		stubCreationUpToTheSave();

		assertSnapshotIsRefused(
				snapshot(recommended(MENTOR_ID, 1, false), recommended(OTHER_MENTOR_ID, 2, true)),
				"select the request mentor exactly once");
	}

	@Test
	void create_refusesASnapshotThatMarksTwoMentorsAsChosen() {
		stubCreationUpToTheSave();

		assertSnapshotIsRefused(
				snapshot(recommended(MENTOR_ID, 1, true), recommended(OTHER_MENTOR_ID, 2, true)),
				"select the request mentor exactly once");
	}

	@Test
	void create_refusesASnapshotWithARepeatedRank() {
		stubCreationUpToTheSave();

		assertSnapshotIsRefused(
				snapshot(recommended(MENTOR_ID, 1, true), recommended(OTHER_MENTOR_ID, 1, false)),
				"ranks and mentors must be unique");
	}

	@Test
	void create_refusesASnapshotThatListsAMentorTwice() {
		stubCreationUpToTheSave();

		assertSnapshotIsRefused(
				snapshot(recommended(MENTOR_ID, 1, true), recommended(MENTOR_ID, 2, false)),
				"ranks and mentors must be unique");
	}

	@Test
	void create_refusesASnapshotNamingSomeoneWhoIsNotAListedMentor() {
		stubCreationUpToTheSave();
		when(mentorProfileService.findAll()).thenReturn(List.of(mentorProfile(MENTOR_ID, "Dr. Kemal Arslan")));

		assertSnapshotIsRefused(
				snapshot(recommended(MENTOR_ID, 1, true), recommended(OTHER_MENTOR_ID, 2, false)),
				"unknown mentor");
	}

	@Test
	void create_refusesEvidenceThatIsNotOnTheMentorsConfirmedProfile() {
		MentorEvidenceResponse confirmed = new MentorEvidenceResponse("sector", "Fintech'te 8 yıl",
				"8 yıl ödeme sistemleri", 2, "v3");
		List<RecommendationEvidenceSubmission> tampered = List.of(
				new RecommendationEvidenceSubmission("stage", "Fintech'te 8 yıl", "8 yıl ödeme sistemleri", 2, "v3"),
				new RecommendationEvidenceSubmission("sector", "Fintech'te 20 yıl", "8 yıl ödeme sistemleri", 2,
						"v3"),
				new RecommendationEvidenceSubmission("sector", "Fintech'te 8 yıl", "20 yıl ödeme sistemleri", 2,
						"v3"),
				new RecommendationEvidenceSubmission("sector", "Fintech'te 8 yıl", "8 yıl ödeme sistemleri", 5,
						"v3"),
				new RecommendationEvidenceSubmission("sector", "Fintech'te 8 yıl", "8 yıl ödeme sistemleri", null,
						"v3"),
				new RecommendationEvidenceSubmission("sector", "Fintech'te 8 yıl", "8 yıl ödeme sistemleri", 2,
						"v2"));
		for (RecommendationEvidenceSubmission evidence : tampered) {
			stubCreationUpToTheSave();
			when(mentorProfileService.findAll())
					.thenReturn(List.of(mentorProfile(MENTOR_ID, "Dr. Kemal Arslan", confirmed)));

			assertThatThrownBy(() -> mentorshipRequestService.create(
					submissionWith("Need guidance", snapshot(recommended(MENTOR_ID, 1, true, evidence)))))
					.as("evidence %s", evidence)
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("mentor's confirmed profile");
		}
		verify(recommendationRunRepository, never()).saveAndFlush(any());
		verifyNoInteractions(notificationService, mailService);
	}

	// --- Deciding: who may answer, which answers exist, and what each one sets off ---

	private MentorshipRequest stubRequestForItsMentor(MentorshipRequestStatus status) {
		MentorshipRequest request = requestIn(status);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(request.getMentor());
		return request;
	}

	private static DecideMentorshipRequest decision(MentorshipRequestStatus status, String message) {
		return new DecideMentorshipRequest(MENTOR_ID, status, message, null);
	}

	@Test
	void decide_refusesAMentorTheRequestWasNotSentTo() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(OTHER_MENTOR_ID))
				.thenReturn(user(OTHER_MENTOR_ID, "selin@mentor.com", "Selin Aksoy", UserRole.MENTOR));

		assertThatThrownBy(() -> mentorshipRequestService.decide(REQUEST_ID, new DecideMentorshipRequest(
				OTHER_MENTOR_ID, MentorshipRequestStatus.ACCEPTED, "Olur", null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the assigned mentor");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void decide_refusesTheAssignedPersonOnceTheyNoLongerHoldTheMentorRole() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(MENTOR_ID))
				.thenReturn(user(MENTOR_ID, "kemal@mentor.com", "Dr. Kemal Arslan", UserRole.MENTEE));

		assertThatThrownBy(() -> mentorshipRequestService.decide(REQUEST_ID,
				decision(MentorshipRequestStatus.ACCEPTED, "Olur")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the assigned mentor");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
	}

	@Test
	void decide_refusesARequestThatIsNoLongerWaitingForTheMentor() {
		for (MentorshipRequestStatus status : List.of(MentorshipRequestStatus.ACCEPTED,
				MentorshipRequestStatus.DECLINED, MentorshipRequestStatus.WITHDRAWN,
				MentorshipRequestStatus.COUNTER_PROPOSED)) {
			MentorshipRequest request = stubRequestForItsMentor(status);

			assertThatThrownBy(() -> mentorshipRequestService.decide(REQUEST_ID,
					decision(MentorshipRequestStatus.DECLINED, "Vazgeçtim")))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only pending requests");
			assertThat(request.getStatus()).isEqualTo(status);
			assertThat(request.getRejectionReason()).isNull();
		}
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void decide_refusesAnAnswerThatIsNotOneOfTheFourDecisions() {
		for (MentorshipRequestStatus notADecision : List.of(MentorshipRequestStatus.PENDING,
				MentorshipRequestStatus.WITHDRAWN)) {
			MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);

			assertThatThrownBy(() -> mentorshipRequestService.decide(REQUEST_ID, decision(notADecision, "Hmm")))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Decision status must be");
			assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		}
		verify(mentorshipRequestRepository, never()).saveAndFlush(any());
	}

	@Test
	void decide_refusesACounterProposalThatNamesNoTime() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);

		assertThatThrownBy(() -> mentorshipRequestService.decide(REQUEST_ID,
				decision(MentorshipRequestStatus.COUNTER_PROPOSED, "Başka bir gün olsun.\nproposedSlots:yarın")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("At least one proposed slot");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		verify(mentorshipRequestMessageRepository, never()).saveAndFlush(any());
	}

	@Test
	void decide_canAnswerARequestThatWasWaitingForMoreInformation() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.MORE_INFO_REQUESTED);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();

		var result = mentorshipRequestService.decide(REQUEST_ID,
				decision(MentorshipRequestStatus.ACCEPTED, "Teşekkürler, başlayalım"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
	}

	@Test
	void decide_decliningKeepsTheReasonAndStartsNothing() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();

		var result = mentorshipRequestService.decide(REQUEST_ID, new DecideMentorshipRequest(MENTOR_ID,
				MentorshipRequestStatus.DECLINED, "  Uzmanlık alanım değil  ", List.of("Pitch deck")));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.DECLINED);
		assertThat(result.rejectionReason()).isEqualTo("Uzmanlık alanım değil");
		assertThat(result.mentorMessage()).isNull();
		verifyNoInteractions(documentRequestService);
		verify(meetingRepository, never()).saveAndFlush(any());
		verify(mentorshipRequestMessageRepository, never()).saveAndFlush(any());
		verify(notificationService).create(request.getRequester(),
				Texts.get(request.getRequester(), "notification.requestDeclined.title"), "Uzmanlık alanım değil",
				"/mentor-request/status?request=" + REQUEST_ID);
	}

	@Test
	void decide_askingForMoreInformationPostsTheQuestionAndTellsTheMentee() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();

		mentorshipRequestService.decide(REQUEST_ID,
				decision(MentorshipRequestStatus.MORE_INFO_REQUESTED, "Gelir modeliniz nedir?"));

		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage question) -> question
						.getMessageType() == MentorshipRequestMessageType.MENTOR_QUESTION
						&& question.getBody().equals("Gelir modeliniz nedir?")
						&& question.getAuthor().getId().equals(MENTOR_ID)));
		assertThat(request.getMentorReadAt()).isNotNull();
		verify(meetingRepository, never()).saveAndFlush(any());
		verify(notificationService).create(request.getRequester(),
				Texts.get(request.getRequester(), "notification.requestInfoNeeded.title"),
				"Gelir modeliniz nedir?", "/mentor-request/status?request=" + REQUEST_ID);
	}

	@Test
	void decide_counterProposingTellsTheMenteeThereAreNewTimes() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();
		String message = "Alternatif öneriyorum.\nproposedSlots:2099-09-12T15:00";

		mentorshipRequestService.decide(REQUEST_ID, decision(MentorshipRequestStatus.COUNTER_PROPOSED, message));

		verify(meetingRepository, never()).saveAndFlush(any());
		verify(notificationService).create(request.getRequester(),
				Texts.get(request.getRequester(), "notification.requestCounterProposed.title"), message,
				"/mentor-request/status?request=" + REQUEST_ID);
	}

	@Test
	void decide_acceptingWithATimeBooksThatMeetingAndCarriesTheDocumentRequestsOver() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		request.setRequesterMessage("preferredSlots:2099-01-05T10:00");
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(call -> call.getArgument(0));
		stubResponseLookups();

		mentorshipRequestService.decide(REQUEST_ID, new DecideMentorshipRequest(MENTOR_ID,
				MentorshipRequestStatus.ACCEPTED, "Görüşme saati: 2099-03-04T11:00", List.of("Pitch deck")));

		ArgumentCaptor<Meeting> booked = ArgumentCaptor.forClass(Meeting.class);
		verify(meetingRepository).saveAndFlush(booked.capture());
		assertThat(booked.getValue().getScheduledAt())
				.isEqualTo(MeetingSlotTimes.parseMachineSlot("2099-03-04T11:00"));
		assertThat(booked.getValue().getDurationMinutes()).isEqualTo(45);
		assertThat(booked.getValue().getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(booked.getValue().getAgenda()).isEqualTo("Product strategy — Mentorluk Görüşmesi");
		assertThat(booked.getValue().getMentorshipRequest()).isSameAs(request);
		verify(documentRequestService).saveTitlesOnRequest(request, request.getMentor(), List.of("Pitch deck"));
		verify(documentRequestService).copyFromRequestToMeeting(request, booked.getValue());
		verify(notificationService).create(request.getRequester(),
				Texts.get(request.getRequester(), "notification.requestAccepted.title"),
				"Görüşme saati: 2099-03-04T11:00", "/mentor-request/status?request=" + REQUEST_ID);
	}

	@Test
	void decide_acceptingARequestThatAlreadyHasAMeetingKeepsThatMeeting() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		Meeting existing = Meeting.forMentorshipRequest(request, slot("00000000-0000-0000-0000-000000000911",
				request.getMentor(), Instant.parse("2099-02-01T09:00:00Z")));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(REQUEST_ID))
				.thenReturn(List.of());
		when(recommendationRunRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
		when(meetingRepository.findByMentorshipRequestId(REQUEST_ID)).thenReturn(Optional.of(existing));

		mentorshipRequestService.decide(REQUEST_ID,
				decision(MentorshipRequestStatus.ACCEPTED, "Görüşme saati: 2099-03-04T11:00"));

		verify(meetingRepository, never()).saveAndFlush(any());
		assertThat(existing.getScheduledAt()).isEqualTo(Instant.parse("2099-02-01T09:00:00Z"));
		verify(documentRequestService).copyFromRequestToMeeting(request, existing);
	}

	@Test
	void decide_acceptingWithNoTimeFromEitherSideLeavesTheMeetingForLater() {
		MentorshipRequest request = stubRequestForItsMentor(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();

		mentorshipRequestService.decide(REQUEST_ID, decision(MentorshipRequestStatus.ACCEPTED, "Olur, görüşelim"));

		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		verify(meetingRepository, never()).saveAndFlush(any());
		verify(documentRequestService, never()).copyFromRequestToMeeting(any(), any());
	}

	// --- Taking a proposed time, and messages from the startup ---

	@Test
	void acceptProposedSlot_booksTheChosenTimeAndTellsTheMentor() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.COUNTER_PROPOSED);
		request.setMentorMessage("Alternatif.\nproposedSlots:2099-09-12T15:00,2099-09-13T10:30");
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(call -> call.getArgument(0));
		stubResponseLookups();

		mentorshipRequestService.acceptProposedSlot(REQUEST_ID,
				new AcceptProposedSlotRequest(REQUESTER_ID, " 2099-09-13T10:30 "));

		verify(meetingRepository).saveAndFlush(argThat((Meeting meeting) -> MeetingSlotTimes
				.parseMachineSlot("2099-09-13T10:30").equals(meeting.getScheduledAt())
				&& meeting.getStatus() == MeetingStatus.SCHEDULED));
		verify(notificationService).create(request.getMentor(), NotificationCategory.MEETING_CHANGE,
				Texts.get(request.getMentor(), "notification.slotChosen.title"),
				Texts.get(request.getMentor(), "notification.slotChosen.body", "Ayşe Demir"), "/mentorships");
	}

	@Test
	void acceptProposedSlot_refusesAnyoneButTheRequester() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.COUNTER_PROPOSED);
		request.setMentorMessage("proposedSlots:2099-09-12T15:00");
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.acceptProposedSlot(REQUEST_ID,
				new AcceptProposedSlotRequest(MENTOR_ID, "2099-09-12T15:00")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the requester");
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
	}

	@Test
	void acceptProposedSlot_refusesARequestTheMentorHasNotCounterProposedOn() {
		for (MentorshipRequestStatus status : List.of(MentorshipRequestStatus.PENDING,
				MentorshipRequestStatus.ACCEPTED, MentorshipRequestStatus.DECLINED)) {
			MentorshipRequest request = requestIn(status);
			request.setMentorMessage("proposedSlots:2099-09-12T15:00");
			when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

			assertThatThrownBy(() -> mentorshipRequestService.acceptProposedSlot(REQUEST_ID,
					new AcceptProposedSlotRequest(REQUESTER_ID, "2099-09-12T15:00")))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("Only counter-proposed requests");
			assertThat(request.getStatus()).isEqualTo(status);
		}
		verifyNoInteractions(meetingRepository);
	}

	@Test
	void acceptProposedSlot_refusesATimeTheMentorDidNotPropose() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.COUNTER_PROPOSED);
		request.setMentorMessage("proposedSlots:2099-09-12T15:00,2099-09-13T10:30");
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

		for (String notProposed : List.of("2099-09-14T09:00", "12 Eylül 15:00")) {
			assertThatThrownBy(() -> mentorshipRequestService.acceptProposedSlot(REQUEST_ID,
					new AcceptProposedSlotRequest(REQUESTER_ID, notProposed)))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("not among the mentor proposals");
		}
		assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
		verifyNoInteractions(meetingRepository, notificationService);
	}

	@Test
	void reply_refusesAnyoneButTheRequester() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.PENDING);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> mentorshipRequestService.reply(REQUEST_ID,
				new ReplyToMentorshipRequest(MENTOR_ID, "Merhaba")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the requester");
		verify(mentorshipRequestMessageRepository, never()).saveAndFlush(any());
	}

	@Test
	void reply_refusesARequestThatIsClosed() {
		for (MentorshipRequestStatus closed : List.of(MentorshipRequestStatus.DECLINED,
				MentorshipRequestStatus.WITHDRAWN)) {
			when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(requestIn(closed)));

			assertThatThrownBy(() -> mentorshipRequestService.reply(REQUEST_ID,
					new ReplyToMentorshipRequest(REQUESTER_ID, "Merhaba")))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("open mentorship request");
		}
		verify(mentorshipRequestMessageRepository, never()).saveAndFlush(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void reply_toACounterProposalIsAnOrdinaryMessageAndLeavesTheProposalOpen() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.COUNTER_PROPOSED);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
		stubResponseLookups();

		var result = mentorshipRequestService.reply(REQUEST_ID,
				new ReplyToMentorshipRequest(REQUESTER_ID, "Cuma olabilir mi?"));

		assertThat(result.status()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
		assertThat(result.menteeReadAt()).isNotNull();
		verify(mentorshipRequestMessageRepository).saveAndFlush(
				argThat((MentorshipRequestMessage message) -> message
						.getMessageType() == MentorshipRequestMessageType.MENTEE_MESSAGE));
	}

	@Test
	void markRead_rejectsSomeoneWithNoStartupWhoIsNotTheMentor() {
		MentorshipRequest request = requestIn(MentorshipRequestStatus.ACCEPTED);
		AppUser stranger = user(OTHER_MENTOR_ID, "selin@mentor.com", "Selin Aksoy", UserRole.MENTOR);
		when(mentorshipRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
		when(domainLookupService.requireUser(OTHER_MENTOR_ID)).thenReturn(stranger);

		assertThatThrownBy(() -> mentorshipRequestService.markRead(REQUEST_ID, OTHER_MENTOR_ID))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not part of this conversation");
		assertThat(request.getMentorReadAt()).isNull();
		assertThat(request.getMenteeReadAt()).isNull();
	}
}
