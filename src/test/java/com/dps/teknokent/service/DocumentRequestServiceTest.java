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

import com.dps.teknokent.domain.NotificationCategory;
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
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamMoveRepository;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamMove;
import com.dps.teknokent.dto.requests.AttachDocumentRequestUploadRequest;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import java.time.Instant;
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
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramTeamMoveRepository programTeamMoveRepository;

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
				eq(NotificationCategory.TASK_ASSIGNED),
				eq("Mentor belgeler istedi"),
				eq("Mentor Ali şu belgeleri istedi: CV"),
				eq("/mentorships?tab=active"));
	}

	@Test
	void saveTitlesOnRequestKeepsEarlierItemsAndAddsOnlyNewTitles() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		MentorshipRequest request = new MentorshipRequest();
		request.setId(UUID.randomUUID());
		request.setOrganization(organization);
		DocumentRequestItem earlier = new DocumentRequestItem();
		earlier.setId(UUID.randomUUID());
		earlier.setTitle("Pitch deck");
		earlier.setSortOrder(0);

		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(request.getId()))
				.thenReturn(List.of(earlier));
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

		documentRequestService.saveTitlesOnRequest(request, mentor, List.of("Pitch deck", "Finansal tablolar"));

		verify(itemRepository, never()).deleteByMentorshipRequestId(any());
		ArgumentCaptor<DocumentRequestItem> captor = ArgumentCaptor.forClass(DocumentRequestItem.class);
		verify(itemRepository).save(captor.capture());
		assertThat(captor.getValue().getTitle()).isEqualTo("Finansal tablolar");
		assertThat(captor.getValue().getSortOrder()).isEqualTo(1);
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
		verify(itemRepository, never()).save(any());
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

	@Test
	void replaceOnProgramKeepsTheItemsStillOnTheChecklistAndDropsTheRest() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(coordinator.getId());
		Organization startup = organization();
		DocumentRequestItem kept = programItem(programId, startup, "Pitch deck", coordinator);
		DocumentRequestItem dropped = programItem(programId, startup, "Eski rapor", coordinator);
		LocalDate dueDate = LocalDate.of(2026, 9, 30);

		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(itemRepository.findByProgramIdOrderBySortOrderAscCreatedAtAsc(programId))
				.thenReturn(List.of(kept, dropped));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(programStartup(programId, startup)));
		when(domainLookupService.requireOrganization(startup.getId())).thenReturn(startup);
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("Finansal tablolar", "Pitch deck"), dueDate);

		assertThat(saved).extracting(DocumentRequestItemResponse::title)
				.containsExactly("Finansal tablolar", "Pitch deck");
		assertThat(saved.get(1).id()).isEqualTo(kept.getId());
		assertThat(kept.getSortOrder()).isEqualTo(1);
		assertThat(kept.getDueDate()).isEqualTo(dueDate);
		verify(itemRepository).deleteAll(List.of(dropped));
	}

	@Test
	void replaceOnProgramDropsWhatAStartupThatLeftStillOwedButKeepsWhatAMovedTeamUploaded() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(coordinator.getId());
		Organization removed = organization();
		Organization moved = organization();
		DocumentRequestItem ofRemoved = programItem(programId, removed, "Pitch deck", coordinator);
		DocumentRequestItem ofMoved = programItem(programId, moved, "Pitch deck", coordinator);

		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(itemRepository.findByProgramIdOrderBySortOrderAscCreatedAtAsc(programId))
				.thenReturn(List.of(ofRemoved, ofMoved));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId)).thenReturn(List.of());
		when(programTeamMoveRepository.findByFromProgramId(programId)).thenReturn(List.of(new ProgramTeamMove(
				moved.getId(), programId, UUID.randomUUID(), UUID.randomUUID(), Instant.now(), Instant.now())));

		documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("Finansal tablolar"), LocalDate.of(2026, 9, 30));

		verify(itemRepository).deleteAll(List.of(ofRemoved));
	}

	@Test
	void listForOrganizationLetsACoordinatorSeeAnyStartup() {
		Organization organization = organization();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(itemRepository.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(organization.getId()))
				.thenReturn(List.of());

		assertThat(documentRequestService.listForOrganization(organization.getId(), coordinator.getId()))
				.isEmpty();
	}

	private static DocumentRequestItem programItem(
			UUID programId, Organization organization, String title, AppUser createdBy) {
		DocumentRequestItem item = new DocumentRequestItem();
		item.setId(UUID.randomUUID());
		item.setTitle(title);
		item.setOrganization(organization);
		item.setProgramId(programId);
		item.setCreatedBy(createdBy);
		return item;
	}

	private static AppUser user(UserRole role, String fullName) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setRole(role);
		user.setFullName(fullName);
		return user;
	}

	// Before dual role any mentor could attach a file to any team's checklist; now only that request's own mentor.
	@Test
	void attachUploadRefusesAMentorWhoIsNotTheItemsMentor() {
		AppUser stranger = user(UserRole.MENTOR, "Başka Mentor");
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Kendi Mentoru"));
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(stranger.getId(), UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class);
		verify(storedDocumentRepository, never()).findById(any());
	}

	@Test
	void attachUploadLetsTheItemsOwnMentorThrough() {
		AppUser ownMentor = user(UserRole.MENTOR, "Kendi Mentoru");
		DocumentRequestItem item = mentorRequestItem(ownMentor);
		UUID documentId = UUID.randomUUID();
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(ownMentor.getId())).thenReturn(ownMentor);
		when(storedDocumentRepository.findById(documentId)).thenReturn(Optional.empty());

		// Past the permission check, the missing document is what stops it.
		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(ownMentor.getId(), documentId)))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	private static DocumentRequestItem mentorRequestItem(AppUser mentor) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(UUID.randomUUID());
		request.setMentor(mentor);
		DocumentRequestItem item = new DocumentRequestItem();
		item.setId(UUID.randomUUID());
		item.setOrganization(organization());
		item.setMentorshipRequest(request);
		return item;
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
