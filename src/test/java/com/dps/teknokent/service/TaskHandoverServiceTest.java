package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class TaskHandoverServiceTest {

	private static final UUID ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");

	private final ActionItemRepository actionItemRepository = mock(ActionItemRepository.class);
	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final AiProviderClient aiProviderClient = mock(AiProviderClient.class);
	private final NotificationService notificationService = mock(NotificationService.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
	private final TaskHandoverService service = serviceWith(TestAiFeatures.allOn());

	private TaskHandoverService serviceWith(AiFeatures aiFeatures) {
		return new TaskHandoverService(actionItemRepository, appUserRepository, organizationRepository,
				aiProviderClient, new LocalPiiRedactionService(), notificationService, eventPublisher, jsonUtils,
				aiFeatures);
	}

	private Organization startup;
	private AppUser leaver;
	private AppUser lead;
	private AppUser designer;
	private ActionItem pitch;
	private ActionItem mockups;

	@BeforeEach
	void setUp() {
		startup = new Organization();
		startup.setId(ORGANIZATION_ID);
		leaver = person("Deniz Yıldız", "Satış");
		lead = person("Ceren Aksoy", "CEO");
		designer = person("Ece Kurt", "Ürün tasarımcısı");
		startup.setFounder(lead);
		pitch = task("Yatırımcı sunumunu güncelle", "Ceren'e deniz@pazar.lab üzerinden gönder");
		mockups = task("Ödeme ekranı taslakları", null);
		when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.of(startup));
		when(actionItemRepository.findByOrganizationIdAndOwnerIdAndStatusIn(ORGANIZATION_ID, leaver.getId(),
				TaskHandoverService.OPEN)).thenReturn(List.of(pitch, mockups));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID))
				.thenReturn(List.of(lead, designer));
	}

	@Test
	void markLeft_keepsWhoHadTheTasksAndStartsTheHandover() {
		service.markLeft(leaver, startup);

		assertThat(pitch.getPreviousOwner()).isSameAs(leaver);
		assertThat(pitch.getOwnerLeftAt()).isNotNull();
		assertThat(pitch.getOwner()).isSameAs(leaver);
		verify(eventPublisher).publishEvent(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));
	}

	@Test
	void markLeft_withoutOpenTasksStartsNothing() {
		when(actionItemRepository.findByOrganizationIdAndOwnerIdAndStatusIn(ORGANIZATION_ID, leaver.getId(),
				TaskHandoverService.OPEN)).thenReturn(List.of());

		service.markLeft(leaver, startup);

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void onMemberLeft_givesEachTaskToTheMemberTheAiPicked() throws Exception {
		service.markLeft(leaver, startup);
		when(aiProviderClient.taskHandover(anyString(), any())).thenReturn(json(
				"{\"assignments\":[{\"task\":\"T1\",\"member\":\"M1\"},{\"task\":\"T2\",\"member\":\"M2\"}]}"));

		service.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(pitch.getOwner()).isSameAs(lead);
		assertThat(mockups.getOwner()).isSameAs(designer);
		assertThat(pitch.getPreviousOwner()).isSameAs(leaver);
		verify(notificationService, times(2)).create(any(AppUser.class), eq(NotificationCategory.TASK_ASSIGNED),
				anyString(), anyString(), eq("/startup/process"));
	}

	@Test
	void onMemberLeft_sendsNoNamesOrContactDetailsToTheAi() {
		ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
		when(aiProviderClient.taskHandover(input.capture(), any())).thenReturn(jsonUtils.createObjectNode());

		service.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(input.getValue()).contains("Ürün tasarımcısı", "Yatırımcı sunumunu güncelle")
				.doesNotContain("Ece Kurt", "Ceren Aksoy", "deniz@pazar.lab");
	}

	@Test
	void onMemberLeft_givesEverythingToTheLeadWhenTheAiFails() {
		when(aiProviderClient.taskHandover(anyString(), any())).thenThrow(new InvalidRequestException("down"));

		service.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(pitch.getOwner()).isSameAs(lead);
		assertThat(mockups.getOwner()).isSameAs(lead);
	}

	@Test
	void onMemberLeft_givesEverythingToTheLeadWithoutAskingTheAiWhenHandoverIsSwitchedOff() {
		serviceWith(TestAiFeatures.allOnExcept(AiFeature.TASK_HANDOVER))
				.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(pitch.getOwner()).isSameAs(lead);
		assertThat(mockups.getOwner()).isSameAs(lead);
		verifyNoInteractions(aiProviderClient);
	}

	@Test
	void onMemberLeft_ignoresIdsTheAiMadeUp() throws Exception {
		when(aiProviderClient.taskHandover(anyString(), any())).thenReturn(json(
				"{\"assignments\":[{\"task\":\"T1\",\"member\":\"M9\"},{\"task\":\"T7\",\"member\":\"M2\"}]}"));

		service.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(pitch.getOwner()).isSameAs(lead);
		assertThat(mockups.getOwner()).isSameAs(lead);
	}

	@Test
	void onMemberLeft_leavesTheTasksWhenNobodyIsLeft() {
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(ORGANIZATION_ID)).thenReturn(List.of());

		service.onMemberLeft(new TeamMemberLeftEvent(ORGANIZATION_ID, leaver.getId()));

		assertThat(pitch.getOwner()).isSameAs(leaver);
		verify(aiProviderClient, never()).taskHandover(anyString(), any());
	}

	private JsonNode json(String value) throws Exception {
		return new ObjectMapper().readTree(value);
	}

	private ActionItem task(String title, String description) {
		ActionItem task = new ActionItem();
		task.setId(UUID.randomUUID());
		task.setOrganization(startup);
		task.setOwner(leaver);
		task.setTitle(title);
		task.setDescription(description);
		task.setStatus(ActionItemStatus.OPEN);
		return task;
	}

	private static AppUser person(String name, String jobTitle) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setJobTitle(jobTitle);
		return user;
	}
}
