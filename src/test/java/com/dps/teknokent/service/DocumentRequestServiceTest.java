package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import java.time.LocalDate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DocumentRequestServiceTest {

	@Mock
	private DocumentRequestItemRepository itemRepository;
	@Mock
	private DocumentRequestUploadRepository uploadRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;

	@InjectMocks
	private DocumentRequestService documentRequestService;

	@Test
	void copyFromRequestToMeetingCopiesTitlesWhenMeetingHasNone() {
		UUID requestId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		AppUser mentor = new AppUser();
		mentor.setId(UUID.randomUUID());
		mentor.setRole(UserRole.MENTOR);
		mentor.setFullName("Mentor");

		MentorshipRequest request = new MentorshipRequest();
		request.setId(requestId);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);

		DocumentRequestItem source = new DocumentRequestItem();
		source.setId(UUID.randomUUID());
		source.setTitle("Pitch deck");
		source.setSortOrder(0);
		source.setCreatedBy(mentor);
		source.setOrganization(organization);
		source.setMentorshipRequest(request);

		when(itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meetingId))
				.thenReturn(List.of());
		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(requestId))
				.thenReturn(List.of(source));
		when(uploadRepository.findByItemIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

		documentRequestService.copyFromRequestToMeeting(request, meeting);

		ArgumentCaptor<DocumentRequestItem> captor = ArgumentCaptor.forClass(DocumentRequestItem.class);
		verify(itemRepository).save(captor.capture());
		assertThat(captor.getValue().getTitle()).isEqualTo("Pitch deck");
		assertThat(captor.getValue().getMeeting()).isEqualTo(meeting);
		assertThat(captor.getValue().getMentorshipRequest()).isNull();
	}

	@Test
	void saveTitlesOnRequestNotifiesOrgMentees() {
		UUID requestId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Startup");
		AppUser mentor = new AppUser();
		mentor.setId(UUID.randomUUID());
		mentor.setRole(UserRole.MENTOR);
		mentor.setFullName("Mentor Ali");
		AppUser mentee = new AppUser();
		mentee.setId(UUID.randomUUID());
		mentee.setRole(UserRole.MENTEE);
		mentee.setFullName("Mentee");

		MentorshipRequest request = new MentorshipRequest();
		request.setId(requestId);
		request.setOrganization(organization);
		request.setMentor(mentor);

		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee));

		documentRequestService.saveTitlesOnRequest(request, mentor, List.of("CV", "  "));

		verify(notificationService).create(
				eq(mentee),
				eq("Mentor belgeler istedi"),
				eq("Mentor Ali şu belgeleri istedi: CV"),
				eq("/mentorships?tab=active"));
	}

	@Test
	void replaceOnProgramAsksEveryStartupInTheProgram() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(coordinator.getId());
		Organization first = organization();
		Organization second = organization();
		LocalDate dueDate = LocalDate.of(2026, 9, 30);

		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(programStartup(programId, first), programStartup(programId, second)));
		when(domainLookupService.requireOrganization(first.getId())).thenReturn(first);
		when(domainLookupService.requireOrganization(second.getId())).thenReturn(second);
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("Finansal tablolar"), dueDate);

		verify(itemRepository).deleteByProgramId(programId);
		assertThat(saved).extracting(DocumentRequestItemResponse::organizationId)
				.containsExactly(first.getId(), second.getId());
		assertThat(saved).allSatisfy(item -> {
			assertThat(item.programId()).isEqualTo(programId);
			assertThat(item.dueDate()).isEqualTo(dueDate);
			assertThat(item.createdByName()).isEqualTo("Koordinatör Ece");
		});
	}

	@Test
	void replaceOnProgramRejectsTheCoordinatorOfAnotherProgram() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(UUID.randomUUID());
		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);

		assertThatThrownBy(() -> documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class);
		verify(itemRepository, never()).deleteByProgramId(any());
	}

	@Test
	void listForOrganizationReturnsEveryRequestToATeamMember() {
		Organization organization = organization();
		AppUser member = user(UserRole.MENTEE, "Gülse Buran");
		member.setOrganization(organization);
		DocumentRequestItem item = new DocumentRequestItem();
		item.setId(UUID.randomUUID());
		item.setTitle("Pitch deck");
		item.setDueDate(LocalDate.of(2026, 9, 20));
		item.setCreatedBy(user(UserRole.MENTOR, "Mentor Ali"));
		item.setOrganization(organization);
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(member.getId())).thenReturn(member);
		when(itemRepository.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(organization.getId()))
				.thenReturn(List.of(item));

		List<DocumentRequestItemResponse> items = documentRequestService.listForOrganization(
				organization.getId(), member.getId());

		assertThat(items).singleElement().satisfies(response -> {
			assertThat(response.createdByName()).isEqualTo("Mentor Ali");
			assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
		});
	}

	@Test
	void listForOrganizationRejectsAnotherStartupsMember() {
		Organization organization = organization();
		AppUser outsider = user(UserRole.MENTEE, "Başka Girişim");
		outsider.setOrganization(organization());
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(outsider.getId())).thenReturn(outsider);

		assertThatThrownBy(() -> documentRequestService.listForOrganization(organization.getId(), outsider.getId()))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static AppUser user(UserRole role, String fullName) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setRole(role);
		user.setFullName(fullName);
		return user;
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		return organization;
	}

	private static ProgramStartup programStartup(UUID programId, Organization organization) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(programId);
		startup.setOrganizationId(organization.getId());
		return startup;
	}
}
