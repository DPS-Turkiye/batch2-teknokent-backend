package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.CoordinatorConversationCounterpart;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.SendCoordinatorMessageRequest;
import com.dps.teknokent.dto.requests.StartCoordinatorConversationRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorConversation;
import com.dps.teknokent.entity.CoordinatorMessage;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorConversationRepository;
import com.dps.teknokent.repository.CoordinatorMessageRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
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
	void theCoordinatorStartsAThreadWithATeamOfTheProgram() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID))
				.thenReturn(Optional.of(new ProgramStartup()));
		when(conversationRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, TERRA_ID)).thenReturn(Optional.empty());
		when(conversationRepository.save(any(CoordinatorConversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(programStartupRepository.findByProgramIdAndOrganizationId(PROGRAM_ID, LUMEN_ID)).thenReturn(Optional.empty());

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
		when(appUserRepository.findAllById(List.of(COORDINATOR_ID))).thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));

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
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(teamConversation()));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(TERRA_ID)).thenReturn(List.of(
				user(FOUNDER_ID, UserRole.MENTEE, TERRA_ID), user(MEMBER_ID, UserRole.MENTEE, TERRA_ID)));

		service.send(COORDINATOR_ID, CONVERSATION_ID, new SendCoordinatorMessageRequest(null, "Ara rapor hatırlatması"));

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
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));
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
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
		when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program()));
		when(appUserRepository.findById(MENTOR_ID)).thenReturn(Optional.of(mentor));

		service.send(COORDINATOR_ID, CONVERSATION_ID, new SendCoordinatorMessageRequest(null, "Perşembe uygun mu?"));

		verify(notificationService).create(eq(mentor), eq(NotificationCategory.MESSAGE), eq("Koordinatörden mesaj"),
				anyString(), eq("/mentor-dashboard/messages?conversation=" + CONVERSATION_ID));
	}

	private void stubNames() {
		when(programRepository.findAllById(any())).thenReturn(List.of(program()));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR, null)));
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
