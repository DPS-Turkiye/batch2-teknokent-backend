package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import com.dps.teknokent.domain.CoordinatorConversationCounterpart;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SendCoordinatorMessageRequest;
import com.dps.teknokent.dto.requests.StartCoordinatorConversationRequest;
import com.dps.teknokent.dto.responses.CoordinatorConversationResponse;
import com.dps.teknokent.dto.responses.CoordinatorMessageResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorConversation;
import com.dps.teknokent.entity.CoordinatorMessage;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorConversationRepository;
import com.dps.teknokent.repository.CoordinatorMessageRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class CoordinatorConversationServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private CoordinatorConversationRepository conversationRepository;
	@Mock
	private CoordinatorMessageRepository messageRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private NotificationService notificationService;
	@InjectMocks
	private CoordinatorConversationService service;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID FOUNDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
	private static final UUID OUTSIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID TERRA_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID LUMEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID CONVERSATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Test
	void anAccountThatMentorsAndIsOnATeamSeesTheThreadsOfBoth() {
		UUID organizationId = UUID.randomUUID();
		AppUser student = user(COORDINATOR_ID, UserRole.MENTEE, organizationId);
		student.grant(UserRole.MENTOR, com.dps.teknokent.domain.PermissionSource.BADGE, java.time.Instant.now());
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(student);

		service.list(COORDINATOR_ID);

		verify(conversationRepository).findByMentorId(COORDINATOR_ID);
		verify(conversationRepository).findByOrganizationId(organizationId);
	}

	@Test
	void aRevokedPrimaryAdminGrantCannotListStaffConversations() {
		AppUser actor = user(COORDINATOR_ID, UserRole.ADMIN, null);
		@SuppressWarnings("unchecked") // getField returns Object; the field is a List<AppUserPermission>.
		var grants = (List<com.dps.teknokent.entity.AppUserPermission>) org.springframework.test.util.ReflectionTestUtils
				.getField(actor, "permissions");
		grants.getFirst().setStatus(com.dps.teknokent.domain.PermissionStatus.REVOKED);
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(actor);

		assertThat(service.list(COORDINATOR_ID)).isEmpty();
		verify(conversationRepository, never()).findAll();
	}

	@Test
	void theCoordinatorStartsAThreadWithATeamOfTheProgram() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID))
				.thenReturn(Optional.of(new ProgramStartup()));
		when(conversationRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID))
				.thenReturn(Optional.empty());
		when(conversationRepository.save(any(CoordinatorConversation.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		stubNames();

		var conversation = service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, TERRA_ID, null));

		assertThat(conversation.counterpartType()).isEqualTo(CoordinatorConversationCounterpart.TEAM);
		assertThat(conversation.counterpartName()).isEqualTo("Terra");
		assertThat(conversation.programName()).isEqualTo("METU Stars 2026");
		assertThat(conversation.unread()).isFalse();
	}

	@Test
	void aTeamOutsideTheProgramCannotBeMessaged() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, LUMEN_ID))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, LUMEN_ID, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(conversationRepository, never()).save(any());
	}

	@Test
	void aTeamReplyNotifiesTheCoordinator() {
		when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(user(FOUNDER_ID, UserRole.MENTEE, TERRA_ID));
		CoordinatorConversation conversation = teamConversation();
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));

		var message = service.send(FOUNDER_ID, CONVERSATION_ID, new SendCoordinatorMessageRequest(null, "  Merhaba  "));

		assertThat(message.body()).isEqualTo("Merhaba");
		assertThat(message.mine()).isTrue();
		assertThat(conversation.getLastMessagePreview()).isEqualTo("Merhaba");
		assertThat(conversation.getCounterpartReadAt()).isNotNull();
		verify(messageRepository).save(any(CoordinatorMessage.class));
		verify(notificationService).create(
				argThat(user -> user != null && COORDINATOR_ID.equals(user.getId())),
				eq(NotificationCategory.MESSAGE),
				eq("Yeni mesaj"),
				anyString(),
				eq("/coordinator/messages?conversation=" + CONVERSATION_ID));
	}

	@Test
	void aCoordinatorMessageNotifiesEveryTeamMember() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(teamConversation()));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(TERRA_ID)).thenReturn(List.of(
				user(FOUNDER_ID, UserRole.MENTEE, TERRA_ID), user(MEMBER_ID, UserRole.MENTEE, TERRA_ID)));

		service.send(COORDINATOR_ID, CONVERSATION_ID,
				new SendCoordinatorMessageRequest(null, "Ara rapor hatırlatması"));

		verify(notificationService, times(2))
				.create(any(), eq(NotificationCategory.MESSAGE), eq("Koordinatörden mesaj"), anyString(),
						eq("/messages?conversation=" + CONVERSATION_ID));
	}

	@Test
	void anotherTeamCannotReadTheThread() {
		when(domainLookupService.requireUser(OUTSIDER_ID)).thenReturn(user(OUTSIDER_ID, UserRole.MENTEE, LUMEN_ID));
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(teamConversation()));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		assertThatThrownBy(() -> service.messages(OUTSIDER_ID, CONVERSATION_ID))
				.isInstanceOf(InvalidRequestException.class);
		verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(any());
	}

	@Test
	void aMentorListsTheirThreadsWithUnreadState() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(CONVERSATION_ID);
		conversation.setProgramId(PROGRAM_ID);
		conversation.setMentorId(MENTOR_ID);
		conversation.setCreatedAt(Instant.parse("2026-09-20T09:00:00Z"));
		conversation.setLastMessageAt(Instant.parse("2026-09-24T09:00:00Z"));
		conversation.setCounterpartReadAt(Instant.parse("2026-09-23T09:00:00Z"));
		conversation.setCoordinatorReadAt(Instant.parse("2026-09-24T09:00:00Z"));
		when(conversationRepository.findByMentorId(MENTOR_ID)).thenReturn(List.of(conversation));
		when(messageRepository.countByConversationIdAndCreatedAtAfter(
				CONVERSATION_ID, Instant.parse("2026-09-23T09:00:00Z"))).thenReturn(2L);
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(
				user(COORDINATOR_ID, UserRole.COORDINATOR, null), user(MENTOR_ID, UserRole.MENTOR, null)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of());

		var conversations = service.list(MENTOR_ID);

		assertThat(conversations).singleElement().satisfies(item -> {
			assertThat(item.counterpartType()).isEqualTo(CoordinatorConversationCounterpart.MENTOR);
			assertThat(item.coordinatorName()).isEqualTo("Elif Demir");
			assertThat(item.unread()).isTrue();
			assertThat(item.unreadCount()).isEqualTo(2);
			assertThat(item.otherSideReadAt()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
		});
	}

	@Test
	void aThreadNeverOpenedCountsEveryMessageAsUnread() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(CONVERSATION_ID);
		conversation.setProgramId(PROGRAM_ID);
		conversation.setMentorId(MENTOR_ID);
		conversation.setCreatedAt(Instant.parse("2026-09-20T09:00:00Z"));
		conversation.setLastMessageAt(Instant.parse("2026-09-24T09:00:00Z"));
		when(conversationRepository.findByMentorId(MENTOR_ID)).thenReturn(List.of(conversation));
		when(messageRepository.countByConversationId(CONVERSATION_ID)).thenReturn(3L);
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));
		when(organizationRepository.findAllById(any())).thenReturn(List.of());

		assertThat(service.list(MENTOR_ID)).singleElement()
				.satisfies(item -> assertThat(item.unreadCount()).isEqualTo(3));
	}

	@Test
	void aCoordinatorMessageLinksTheMentorToTheThread() {
		CoordinatorConversation conversation = teamConversation();
		conversation.setOrganizationId(null);
		conversation.setMentorId(MENTOR_ID);
		AppUser mentor = user(MENTOR_ID, UserRole.MENTOR, null);
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findById(MENTOR_ID)).thenReturn(Optional.of(mentor));

		service.send(COORDINATOR_ID, CONVERSATION_ID, new SendCoordinatorMessageRequest(null, "Perşembe uygun mu?"));

		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MESSAGE), eq("Koordinatörden mesaj"),
				anyString(), eq("/mentor-dashboard/messages?conversation=" + CONVERSATION_ID));
	}

	@Test
	void anAdminListsEveryConversationNewestActivityFirstFromTheCoordinatorSide() {
		when(domainLookupService.requireUser(OUTSIDER_ID)).thenReturn(user(OUTSIDER_ID, UserRole.ADMIN, null));
		CoordinatorConversation quiet = teamConversation();
		CoordinatorConversation busy = mentorConversation();
		busy.setLastMessageAt(Instant.parse("2026-09-24T09:00:00Z"));
		busy.setCoordinatorReadAt(Instant.parse("2026-09-24T09:00:00Z"));
		busy.setCounterpartReadAt(Instant.parse("2026-09-22T09:00:00Z"));
		when(conversationRepository.findAll()).thenReturn(List.of(quiet, busy));
		stubNames();

		var conversations = service.list(OUTSIDER_ID);

		assertThat(conversations)
				.extracting(CoordinatorConversationResponse::id, CoordinatorConversationResponse::counterpartName,
						CoordinatorConversationResponse::unread)
				.containsExactly(tuple(busy.getId(), "Mentor", false), tuple(CONVERSATION_ID, "Terra", false));
		// The coordinator side is shown when the other side last read the thread.
		assertThat(conversations.getFirst().otherSideReadAt()).isEqualTo(Instant.parse("2026-09-22T09:00:00Z"));
		assertThat(conversations.getFirst().unreadCount()).isZero();
		verifyNoInteractions(messageRepository);
	}

	@Test
	void aCoordinatorListsTheConversationsOfTheProgramsTheyRun() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program()));
		CoordinatorConversation conversation = teamConversation();
		conversation.setLastMessageAt(Instant.parse("2026-09-24T09:00:00Z"));
		conversation.setLastMessagePreview("Ara raporu yükledik.");
		when(conversationRepository.findByProgramIdIn(List.of(PROGRAM_ID))).thenReturn(List.of(conversation));
		when(messageRepository.countByConversationId(CONVERSATION_ID)).thenReturn(4L);
		stubNames();

		assertThat(service.list(COORDINATOR_ID)).singleElement().satisfies(item -> {
			assertThat(item.counterpartId()).isEqualTo(TERRA_ID);
			assertThat(item.coordinatorId()).isEqualTo(COORDINATOR_ID);
			assertThat(item.lastMessagePreview()).isEqualTo("Ara raporu yükledik.");
			assertThat(item.unread()).isTrue();
			assertThat(item.unreadCount()).isEqualTo(4);
		});
		verify(conversationRepository, never()).findAll();
	}

	@Test
	void aCoordinatorWithoutProgramsHasNoConversations() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));

		assertThat(service.list(COORDINATOR_ID)).isEmpty();

		verifyNoInteractions(conversationRepository);
	}

	@Test
	void aStudentWithoutATeamHasNoThreads() {
		when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(user(FOUNDER_ID, UserRole.MENTEE, null));

		assertThat(service.list(FOUNDER_ID)).isEmpty();

		verifyNoInteractions(conversationRepository);
	}

	@Test
	void aThreadReadSinceItsLastMessageIsNotUnread() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		CoordinatorConversation conversation = mentorConversation();
		conversation.setLastMessageAt(Instant.parse("2026-09-24T09:00:00Z"));
		conversation.setCounterpartReadAt(Instant.parse("2026-09-24T09:00:00Z"));
		when(conversationRepository.findByMentorId(MENTOR_ID)).thenReturn(List.of(conversation));
		stubNames();

		assertThat(service.list(MENTOR_ID)).singleElement().satisfies(item -> {
			assertThat(item.unread()).isFalse();
			assertThat(item.unreadCount()).isZero();
		});
		verifyNoInteractions(messageRepository);
	}

	@Test
	void aThreadWhoseProgramCoordinatorOrCounterpartIsGoneStillLists() {
		AppUser actor = user(FOUNDER_ID, UserRole.MENTEE, TERRA_ID);
		actor.grant(UserRole.MENTOR, PermissionSource.BADGE, Instant.now());
		when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(actor);
		CoordinatorConversation ofAGoneProgram = mentorConversation();
		ofAGoneProgram.setProgramId(UUID.randomUUID());
		ofAGoneProgram.setMentorId(FOUNDER_ID);
		ofAGoneProgram.setCreatedAt(Instant.parse("2026-09-21T09:00:00Z"));
		when(conversationRepository.findByMentorId(FOUNDER_ID)).thenReturn(List.of(ofAGoneProgram));
		// The mentor thread comes back from both queries and is listed once.
		when(conversationRepository.findByOrganizationId(TERRA_ID))
				.thenReturn(List.of(teamConversation(), ofAGoneProgram));
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of());
		when(organizationRepository.findAllById(any())).thenReturn(List.of());

		var conversations = service.list(FOUNDER_ID);

		assertThat(conversations)
				.extracting(CoordinatorConversationResponse::id, CoordinatorConversationResponse::programName,
						CoordinatorConversationResponse::coordinatorId,
						CoordinatorConversationResponse::coordinatorName,
						CoordinatorConversationResponse::counterpartName)
				.containsExactly(
						tuple(ofAGoneProgram.getId(), null, null, null, "Mentor"),
						tuple(CONVERSATION_ID, "METU Stars 2026", COORDINATOR_ID, null, "Takım"));
	}

	@Test
	void onlyTheProgramsCoordinatorStartsAConversation() {
		when(domainLookupService.requireUser(OUTSIDER_ID)).thenReturn(user(OUTSIDER_ID, UserRole.COORDINATOR, null));
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		var request = new StartCoordinatorConversationRequest(null, PROGRAM_ID, TERRA_ID, null);

		assertThatThrownBy(() -> service.start(OUTSIDER_ID, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the program coordinator");
		assertThatThrownBy(() -> service.start(MENTOR_ID, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the program coordinator");
		verifyNoInteractions(conversationRepository);
	}

	@Test
	void aConversationIsWithEitherATeamOrAMentor() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		assertThatThrownBy(() -> service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, TERRA_ID, MENTOR_ID)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("either a team or a mentor");
		assertThatThrownBy(() -> service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("either a team or a mentor");
		verifyNoInteractions(conversationRepository);
	}

	@Test
	void startingAConversationInAProgramThatDoesNotExistIsNotFound() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, TERRA_ID, null)))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(PROGRAM_ID.toString());
	}

	@Test
	void aMentorOutsideTheProgramCannotBeMessaged() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, null, MENTOR_ID)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentor is not in the program");
		verifyNoInteractions(conversationRepository);
	}

	@Test
	void anAdminStartsAThreadWithAMentorOfAnyProgram() {
		when(domainLookupService.requireUser(OUTSIDER_ID)).thenReturn(user(OUTSIDER_ID, UserRole.ADMIN, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programMentorRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID))
				.thenReturn(Optional.of(new ProgramMentor()));
		when(conversationRepository.findByProgramIdAndMentorId(PROGRAM_ID, MENTOR_ID)).thenReturn(Optional.empty());
		when(conversationRepository.save(any(CoordinatorConversation.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		AppUser mentor = user(MENTOR_ID, UserRole.MENTOR, null);
		mentor.setFullName("Ece Demir");
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null), mentor));

		var conversation = service.start(OUTSIDER_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, null, MENTOR_ID));

		assertThat(conversation.counterpartType()).isEqualTo(CoordinatorConversationCounterpart.MENTOR);
		assertThat(conversation.counterpartId()).isEqualTo(MENTOR_ID);
		assertThat(conversation.counterpartName()).isEqualTo("Ece Demir");
		assertThat(conversation.coordinatorName()).isEqualTo("Elif Demir");
		ArgumentCaptor<CoordinatorConversation> saved = ArgumentCaptor.forClass(CoordinatorConversation.class);
		verify(conversationRepository).save(saved.capture());
		assertThat(saved.getValue().getProgramId()).isEqualTo(PROGRAM_ID);
		assertThat(saved.getValue().getMentorId()).isEqualTo(MENTOR_ID);
		assertThat(saved.getValue().getOrganizationId()).isNull();
		assertThat(saved.getValue().getCreatedAt()).isNotNull();
	}

	@Test
	void startingAgainReturnsTheExistingThreadInsteadOfASecondOne() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID))
				.thenReturn(Optional.of(new ProgramStartup()));
		when(conversationRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID))
				.thenReturn(Optional.of(teamConversation()));
		stubNames();

		var conversation = service.start(COORDINATOR_ID,
				new StartCoordinatorConversationRequest(null, PROGRAM_ID, TERRA_ID, null));

		assertThat(conversation.id()).isEqualTo(CONVERSATION_ID);
		verify(conversationRepository, never()).save(any());
	}

	@Test
	void openingAThreadMarksItReadForTheReadersSideAndFlagsTheirOwnMessages() {
		AppUser founder = user(FOUNDER_ID, UserRole.MENTEE, TERRA_ID);
		when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(founder);
		CoordinatorConversation conversation = teamConversation();
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID)).thenReturn(List.of(
				message(null, "Hoş geldiniz.", "2026-09-20T09:00:00Z"),
				message(COORDINATOR_ID, "Ara rapor ne durumda?", "2026-09-21T09:00:00Z"),
				message(FOUNDER_ID, "Yarın gönderiyoruz.", "2026-09-21T10:00:00Z")));
		when(appUserRepository.findAllById(Set.of(COORDINATOR_ID, FOUNDER_ID)))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null), founder));

		var messages = service.messages(FOUNDER_ID, CONVERSATION_ID);

		assertThat(messages)
				.extracting(CoordinatorMessageResponse::senderName, CoordinatorMessageResponse::mine,
						CoordinatorMessageResponse::body)
				.containsExactly(
						tuple(null, false, "Hoş geldiniz."),
						tuple("Elif Demir", false, "Ara rapor ne durumda?"),
						tuple("Kullanıcı", true, "Yarın gönderiyoruz."));
		assertThat(conversation.getCounterpartReadAt()).isNotNull();
		assertThat(conversation.getCoordinatorReadAt()).isNull();
	}

	@Test
	void aMentorReadsTheirOwnThreadAndTheCoordinatorTheirs() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		CoordinatorConversation conversation = mentorConversation();
		when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));

		assertThat(service.messages(MENTOR_ID, conversation.getId())).isEmpty();
		assertThat(conversation.getCounterpartReadAt()).isNotNull();
		assertThat(conversation.getCoordinatorReadAt()).isNull();

		service.messages(COORDINATOR_ID, conversation.getId());
		assertThat(conversation.getCoordinatorReadAt()).isNotNull();
	}

	@Test
	void aThreadThatDoesNotExistOrWhoseProgramIsGoneIsNotFound() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(conversationRepository.findById(CONVERSATION_ID))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(teamConversation()));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.messages(COORDINATOR_ID, CONVERSATION_ID))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Conversation not found");
		assertThatThrownBy(() -> service.send(COORDINATOR_ID, CONVERSATION_ID,
				new SendCoordinatorMessageRequest(null, "Merhaba")))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining("Program not found");
		verifyNoInteractions(messageRepository, notificationService);
	}

	@Test
	void someoneOutsideTheThreadCannotWriteToIt() {
		when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
		when(domainLookupService.requireUser(OUTSIDER_ID)).thenReturn(user(OUTSIDER_ID, UserRole.COORDINATOR, null));
		CoordinatorConversation conversation = teamConversation();
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		var request = new SendCoordinatorMessageRequest(null, "Merhaba");

		// A mentor on no team, and a coordinator of some other program.
		assertThatThrownBy(() -> service.send(MENTOR_ID, CONVERSATION_ID, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not part of this conversation");
		assertThatThrownBy(() -> service.send(OUTSIDER_ID, CONVERSATION_ID, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not part of this conversation");
		assertThat(conversation.getLastMessageAt()).isNull();
		verifyNoInteractions(messageRepository, notificationService);
	}

	@Test
	void sendingKeepsTheWholeMessageButCutsTheThreadsPreviewToTwoHundredCharacters() {
		when(domainLookupService.requireUser(COORDINATOR_ID))
				.thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		CoordinatorConversation conversation = teamConversation();
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		String body = "a".repeat(200) + "b".repeat(50);

		var message = service.send(COORDINATOR_ID, CONVERSATION_ID, new SendCoordinatorMessageRequest(null, body));

		assertThat(message.body()).isEqualTo(body);
		assertThat(message.senderName()).isEqualTo("Elif Demir");
		assertThat(conversation.getLastMessagePreview()).isEqualTo("a".repeat(200));
		// The sender has read what they wrote; the other side has not.
		assertThat(conversation.getCoordinatorReadAt()).isEqualTo(conversation.getLastMessageAt());
		assertThat(conversation.getCounterpartReadAt()).isNull();
		ArgumentCaptor<CoordinatorMessage> saved = ArgumentCaptor.forClass(CoordinatorMessage.class);
		verify(messageRepository).save(saved.capture());
		assertThat(saved.getValue().getConversationId()).isEqualTo(CONVERSATION_ID);
		assertThat(saved.getValue().getSenderId()).isEqualTo(COORDINATOR_ID);
		assertThat(saved.getValue().getBody()).isEqualTo(body);
	}

	private void stubNames() {
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		when(appUserRepository.findAllById(any()))
				.thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));
		Organization terra = new Organization();
		terra.setId(TERRA_ID);
		terra.setName("Terra");
		when(organizationRepository.findAllById(any())).thenReturn(List.of(terra));
	}

	private static CoordinatorConversation teamConversation() {
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(CONVERSATION_ID);
		conversation.setProgramId(PROGRAM_ID);
		conversation.setOrganizationId(TERRA_ID);
		conversation.setCreatedAt(Instant.parse("2026-09-20T09:00:00Z"));
		return conversation;
	}

	private static CoordinatorConversation mentorConversation() {
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
		conversation.setProgramId(PROGRAM_ID);
		conversation.setMentorId(MENTOR_ID);
		conversation.setCreatedAt(Instant.parse("2026-09-19T09:00:00Z"));
		return conversation;
	}

	private static CoordinatorMessage message(UUID senderId, String body, String createdAt) {
		CoordinatorMessage message = new CoordinatorMessage();
		message.setId(UUID.randomUUID());
		message.setConversationId(CONVERSATION_ID);
		message.setSenderId(senderId);
		message.setBody(body);
		message.setCreatedAt(Instant.parse(createdAt));
		return message;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(COORDINATOR_ID);
		program.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
		return program;
	}

	private static AppUser user(UUID id, UserRole role, UUID organizationId) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setEmail(id + "@tekno.test");
		user.setFullName(role == UserRole.COORDINATOR ? "Elif Demir" : "Kullanıcı");
		if (organizationId != null) {
			Organization organization = new Organization();
			organization.setId(organizationId);
			user.setOrganization(organization);
		}
		return user;
	}
}
