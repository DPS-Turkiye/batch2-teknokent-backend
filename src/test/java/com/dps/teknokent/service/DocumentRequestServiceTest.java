package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AttachDocumentRequestUploadRequest;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.dto.responses.DocumentRequestUploadResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.DocumentRequestUpload;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramTeamMove;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamMoveRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

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
	void listForOrganizationLetsTheCoordinatorOfTheStartupsProgramSeeIt() {
		Organization organization = organization();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setCreatedBy(UUID.randomUUID());
		DocumentRequestItem item = item("Pitch deck", user(UserRole.MENTOR, "Mentor Ali"), organization);
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()))
				.thenReturn(List.of(programStartup(program.getId(), organization)));
		when(programRepository.findAllById(List.of(program.getId()))).thenReturn(List.of(program));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(program.getId(), coordinator.getId()))
				.thenReturn(true);
		when(itemRepository.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(organization.getId()))
				.thenReturn(List.of(item));

		assertThat(documentRequestService.listForOrganization(organization.getId(), coordinator.getId()))
				.extracting(DocumentRequestItemResponse::id)
				.containsExactly(item.getId());
	}

	@Test
	void listForOrganizationRejectsACoordinatorWhoDoesNotRunTheStartupsProgram() {
		Organization organization = organization();
		AppUser coordinator = user(UserRole.COORDINATOR, "Başka Koordinatör");
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setCreatedBy(UUID.randomUUID());
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()))
				.thenReturn(List.of(programStartup(program.getId(), organization)));
		when(programRepository.findAllById(List.of(program.getId()))).thenReturn(List.of(program));

		assertThatThrownBy(() -> documentRequestService.listForOrganization(organization.getId(), coordinator.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(itemRepository, never()).findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(any());
	}

	@Test
	void listForOrganizationRejectsACoordinatorWhenTheStartupIsInNoProgram() {
		Organization organization = organization();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()))
				.thenReturn(List.of());

		assertThatThrownBy(() -> documentRequestService.listForOrganization(organization.getId(), coordinator.getId()))
				.isInstanceOf(InvalidRequestException.class);
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

	@Test
	void listReturnsAMeetingsChecklistWithTheFilesUploadedForEachItem() {
		Organization organization = organization();
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		AppUser mentee = teamMember(organization, "Gülse Buran");
		Meeting meeting = meeting(mentor, organization);
		DocumentRequestItem deck = item("Pitch deck", mentor, organization);
		deck.setMeeting(meeting);
		DocumentRequestItem financials = item("Finansal tablolar", mentor, organization);
		financials.setMeeting(meeting);
		financials.setSortOrder(1);
		StoredDocument file = storedDocument(mentee.getId());
		DocumentRequestUpload upload = upload(financials, file, mentee);
		when(itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meeting.getId()))
				.thenReturn(List.of(deck, financials));
		when(uploadRepository.findByItemIdInOrderByCreatedAtAsc(List.of(deck.getId(), financials.getId())))
				.thenReturn(List.of(upload));

		List<DocumentRequestItemResponse> items = documentRequestService.list(null, meeting.getId(), null);

		assertThat(items).extracting(DocumentRequestItemResponse::title)
				.containsExactly("Pitch deck", "Finansal tablolar");
		assertThat(items).allSatisfy(item -> {
			assertThat(item.meetingId()).isEqualTo(meeting.getId());
			assertThat(item.mentorshipRequestId()).isNull();
			assertThat(item.mentorshipId()).isNull();
		});
		assertThat(items.get(0).uploads()).isEmpty();
		assertThat(items.get(1).uploads()).containsExactly(new DocumentRequestUploadResponse(
				upload.getId(), file.getId(), "pitch-deck.pdf", "application/pdf", 2048, mentee.getId(),
				"Gülse Buran", null));
	}

	@Test
	void listReturnsAMentorshipRequestsChecklist() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		DocumentRequestItem item = mentorRequestItem(mentor);
		item.setTitle("CV");
		item.setCreatedBy(mentor);
		UUID requestId = item.getMentorshipRequest().getId();
		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(requestId))
				.thenReturn(List.of(item));

		List<DocumentRequestItemResponse> items = documentRequestService.list(requestId, null, null);

		assertThat(items).singleElement().satisfies(response -> {
			assertThat(response.id()).isEqualTo(item.getId());
			assertThat(response.mentorshipRequestId()).isEqualTo(requestId);
			assertThat(response.meetingId()).isNull();
			assertThat(response.mentorshipId()).isNull();
			assertThat(response.uploads()).isEmpty();
		});
	}

	@Test
	void listReturnsAMentorshipsChecklist() {
		Organization organization = organization();
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Mentorship mentorship = mentorship(mentor, organization);
		DocumentRequestItem item = item("Aylık rapor", mentor, organization);
		item.setMentorship(mentorship);
		when(itemRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorship.getId()))
				.thenReturn(List.of(item));

		List<DocumentRequestItemResponse> items = documentRequestService.list(null, null, mentorship.getId());

		assertThat(items).singleElement().satisfies(response -> {
			assertThat(response.title()).isEqualTo("Aylık rapor");
			assertThat(response.mentorshipId()).isEqualTo(mentorship.getId());
			assertThat(response.mentorshipRequestId()).isNull();
			assertThat(response.meetingId()).isNull();
		});
	}

	@Test
	void listRefusesAnythingButExactlyOneTarget() {
		UUID requestId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		UUID mentorshipId = UUID.randomUUID();

		assertThatThrownBy(() -> documentRequestService.list(null, null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("exactly one");
		assertThatThrownBy(() -> documentRequestService.list(requestId, meetingId, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> documentRequestService.list(requestId, null, mentorshipId))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> documentRequestService.list(null, meetingId, mentorshipId))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> documentRequestService.list(requestId, meetingId, mentorshipId))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(itemRepository);
	}

	@Test
	void listForOrganizationLetsAnAdminSeeAnyStartup() {
		Organization organization = organization();
		AppUser admin = user(UserRole.ADMIN, "Admin Deniz");
		DocumentRequestItem item = item("Pitch deck", user(UserRole.MENTOR, "Mentor Ali"), organization);
		when(domainLookupService.requireOrganization(organization.getId())).thenReturn(organization);
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);
		when(itemRepository.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(organization.getId()))
				.thenReturn(List.of(item));

		assertThat(documentRequestService.listForOrganization(organization.getId(), admin.getId()))
				.extracting(DocumentRequestItemResponse::id)
				.containsExactly(item.getId());
	}

	@Test
	void replaceOnRequestReplacesTheChecklistAndTellsEachMenteeInTheirLanguage() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		MentorshipRequest request = mentorshipRequest(mentor, organization);
		AppUser mentee = teamMember(organization, "Gülse Buran");
		AppUser englishReader = teamMember(organization, "Sude Arya");
		englishReader.setPreferredLanguage(AppLanguage.EN);
		AppUser notAMentee = user(UserRole.COORDINATOR, "Koordinatör Ece");
		LocalDate dueDate = LocalDate.of(2026, 9, 30);
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		stubItemSaves();
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee, englishReader, notAMentee));

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnRequest(
				request.getId(), mentor.getId(), List.of("  Pitch deck ", "   ", "Finansal tablolar"), dueDate);

		// The old rows must be gone from the database before the new ones are written.
		InOrder order = inOrder(itemRepository);
		order.verify(itemRepository).deleteByMentorshipRequestId(request.getId());
		order.verify(itemRepository).flush();
		order.verify(itemRepository, times(2)).save(any(DocumentRequestItem.class));
		assertThat(saved).extracting(DocumentRequestItemResponse::title, DocumentRequestItemResponse::sortOrder)
				.containsExactly(tuple("Pitch deck", 0), tuple("Finansal tablolar", 1));
		assertThat(saved).allSatisfy(item -> {
			assertThat(item.mentorshipRequestId()).isEqualTo(request.getId());
			assertThat(item.meetingId()).isNull();
			assertThat(item.mentorshipId()).isNull();
			assertThat(item.programId()).isNull();
			assertThat(item.organizationId()).isEqualTo(organization.getId());
			assertThat(item.dueDate()).isEqualTo(dueDate);
			assertThat(item.createdById()).isEqualTo(mentor.getId());
		});
		verify(notificationService).create(mentee, NotificationCategory.TASK_ASSIGNED, "Mentor belgeler istedi",
				"Mentor Ali şu belgeleri istedi: Pitch deck, Finansal tablolar", "/mentorships?tab=active");
		verify(notificationService).create(englishReader, NotificationCategory.TASK_ASSIGNED,
				"Your mentor asked for documents",
				"Mentor Ali asked for these documents: Pitch deck, Finansal tablolar", "/mentorships?tab=active");
		verifyNoMoreInteractions(notificationService);
	}

	@Test
	void replaceOnRequestRefusesAMentorWhoIsNotTheRequestsMentor() {
		AppUser stranger = user(UserRole.MENTOR, "Başka Mentor");
		MentorshipRequest request = mentorshipRequest(user(UserRole.MENTOR, "Kendi Mentoru"), organization());
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		assertThatThrownBy(() -> documentRequestService.replaceOnRequest(
				request.getId(), stranger.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("assigned mentor");
		verifyNoInteractions(itemRepository, notificationService);
	}

	@Test
	void replaceOnRequestRefusesItsMentorOnceTheyNoLongerHoldTheMentorRole() {
		AppUser formerMentor = user(UserRole.MENTEE, "Eski Mentor");
		MentorshipRequest request = mentorshipRequest(formerMentor, organization());
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(formerMentor.getId())).thenReturn(formerMentor);

		assertThatThrownBy(() -> documentRequestService.replaceOnRequest(
				request.getId(), formerMentor.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnRequestWithNoTitlesEmptiesTheChecklistAndNotifiesNobody() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		MentorshipRequest request = mentorshipRequest(mentor, organization());
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnRequest(
				request.getId(), mentor.getId(), null, null);

		assertThat(saved).isEmpty();
		verify(itemRepository).deleteByMentorshipRequestId(request.getId());
		verify(itemRepository, never()).save(any());
		verifyNoInteractions(notificationService, appUserRepository);
	}

	@Test
	void acceptsATitleOfExactlyFiveHundredCharactersOnceTrimmed() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		MentorshipRequest request = mentorshipRequest(mentor, organization());
		String longest = "a".repeat(500);
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		stubItemSaves();

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnRequest(
				request.getId(), mentor.getId(), List.of("  " + longest + "  "), null);

		assertThat(saved).extracting(DocumentRequestItemResponse::title).containsExactly(longest);
	}

	@Test
	void rejectsATitleLongerThanFiveHundredCharacters() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		MentorshipRequest request = mentorshipRequest(mentor, organization());
		when(domainLookupService.requireMentorshipRequest(request.getId())).thenReturn(request);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);

		assertThatThrownBy(() -> documentRequestService.replaceOnRequest(
				request.getId(), mentor.getId(), List.of("CV", "a".repeat(501)), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("500");
		verify(itemRepository, never()).save(any());
		verifyNoInteractions(notificationService);
	}

	@Test
	void replaceOnMeetingReplacesTheChecklistAndLinksTheNotificationToTheMeeting() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		Meeting meeting = meeting(mentor, organization);
		AppUser mentee = teamMember(organization, "Gülse Buran");
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		stubItemSaves();
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee));

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnMeeting(
				meeting.getId(), mentor.getId(), List.of("Kullanıcı akışı"), null);

		InOrder order = inOrder(itemRepository);
		order.verify(itemRepository).deleteByMeetingId(meeting.getId());
		order.verify(itemRepository).flush();
		order.verify(itemRepository).save(any(DocumentRequestItem.class));
		assertThat(saved).singleElement().satisfies(item -> {
			assertThat(item.title()).isEqualTo("Kullanıcı akışı");
			assertThat(item.meetingId()).isEqualTo(meeting.getId());
			assertThat(item.mentorshipRequestId()).isNull();
			assertThat(item.mentorshipId()).isNull();
			assertThat(item.organizationId()).isEqualTo(organization.getId());
		});
		verify(notificationService).create(mentee, NotificationCategory.TASK_ASSIGNED, "Mentor belgeler istedi",
				"Mentor Ali şu belgeleri istedi: Kullanıcı akışı", "/meetings/research?id=" + meeting.getId());
	}

	@Test
	void replaceOnMeetingRefusesSomeoneWhoIsNotAMentor() {
		Organization organization = organization();
		Meeting meeting = meeting(user(UserRole.MENTOR, "Mentor Ali"), organization);
		AppUser mentee = teamMember(organization, "Gülse Buran");
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);

		assertThatThrownBy(() -> documentRequestService.replaceOnMeeting(
				meeting.getId(), mentee.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only mentors");
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnMeetingRefusesAMentorOfAnotherMeeting() {
		AppUser stranger = user(UserRole.MENTOR, "Başka Mentor");
		Meeting meeting = meeting(user(UserRole.MENTOR, "Mentor Ali"), organization());
		when(domainLookupService.requireMeeting(meeting.getId())).thenReturn(meeting);
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		assertThatThrownBy(() -> documentRequestService.replaceOnMeeting(
				meeting.getId(), stranger.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("meeting mentor");
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnMentorshipReplacesTheChecklistAndLinksTheNotificationToRegularMentorships() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		Mentorship mentorship = mentorship(mentor, organization);
		AppUser mentee = teamMember(organization, "Gülse Buran");
		LocalDate dueDate = LocalDate.of(2026, 10, 15);
		when(mentorshipRepository.findById(mentorship.getId())).thenReturn(Optional.of(mentorship));
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);
		stubItemSaves();
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee));

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnMentorship(
				mentorship.getId(), mentor.getId(), List.of("Aylık rapor"), dueDate);

		InOrder order = inOrder(itemRepository);
		order.verify(itemRepository).deleteByMentorshipId(mentorship.getId());
		order.verify(itemRepository).flush();
		order.verify(itemRepository).save(any(DocumentRequestItem.class));
		assertThat(saved).singleElement().satisfies(item -> {
			assertThat(item.mentorshipId()).isEqualTo(mentorship.getId());
			assertThat(item.mentorshipRequestId()).isNull();
			assertThat(item.meetingId()).isNull();
			assertThat(item.dueDate()).isEqualTo(dueDate);
		});
		verify(notificationService).create(mentee, NotificationCategory.TASK_ASSIGNED, "Mentor belgeler istedi",
				"Mentor Ali şu belgeleri istedi: Aylık rapor", "/mentorships?tab=regular");
	}

	@Test
	void replaceOnMentorshipReportsAnUnknownMentorship() {
		UUID mentorshipId = UUID.randomUUID();
		when(mentorshipRepository.findById(mentorshipId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentRequestService.replaceOnMentorship(
				mentorshipId, UUID.randomUUID(), List.of("CV"), null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(mentorshipId.toString());
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnMentorshipRefusesAMentorOfAnotherMentorship() {
		AppUser stranger = user(UserRole.MENTOR, "Başka Mentor");
		Mentorship mentorship = mentorship(user(UserRole.MENTOR, "Mentor Ali"), organization());
		when(mentorshipRepository.findById(mentorship.getId())).thenReturn(Optional.of(mentorship));
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		assertThatThrownBy(() -> documentRequestService.replaceOnMentorship(
				mentorship.getId(), stranger.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnProgramReportsAnUnknownProgram() {
		UUID programId = UUID.randomUUID();
		when(programRepository.findById(programId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentRequestService.replaceOnProgram(
				programId, UUID.randomUUID(), List.of("CV"), null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(programId.toString());
	}

	@Test
	void replaceOnProgramRefusesItsCreatorWhenTheyAreNotACoordinator() {
		UUID programId = UUID.randomUUID();
		AppUser admin = user(UserRole.ADMIN, "Admin Deniz");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(admin.getId());
		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(admin.getId())).thenReturn(admin);

		assertThatThrownBy(() -> documentRequestService.replaceOnProgram(
				programId, admin.getId(), List.of("CV"), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("program coordinator");
		verifyNoInteractions(itemRepository);
	}

	@Test
	void replaceOnProgramLetsACoordinatorWhoWasAddedToTheProgram() {
		UUID programId = UUID.randomUUID();
		AppUser added = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(UUID.randomUUID());
		Organization startup = organization();
		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(added.getId())).thenReturn(added);
		when(programCoordinatorRepository.existsByProgramIdAndUserId(programId, added.getId())).thenReturn(true);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(programStartup(programId, startup)));
		when(domainLookupService.requireOrganization(startup.getId())).thenReturn(startup);
		stubItemSaves();

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnProgram(
				programId, added.getId(), List.of("Finansal tablolar"), null);

		assertThat(saved).singleElement().satisfies(item -> {
			assertThat(item.organizationId()).isEqualTo(startup.getId());
			assertThat(item.createdById()).isEqualTo(added.getId());
		});
	}

	@Test
	void replaceOnProgramTellsAStartupOnlyAboutTheTitlesItDidNotAlreadyOwe() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(coordinator.getId());
		Organization upToDate = organization();
		Organization behind = organization();
		AppUser menteeOfBehind = teamMember(behind, "Gülse Buran");
		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(itemRepository.findByProgramIdOrderBySortOrderAscCreatedAtAsc(programId)).thenReturn(List.of(
				programItem(programId, upToDate, "Pitch deck", coordinator),
				programItem(programId, upToDate, "Finansal tablolar", coordinator),
				programItem(programId, behind, "Pitch deck", coordinator)));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(programStartup(programId, upToDate), programStartup(programId, behind)));
		when(domainLookupService.requireOrganization(upToDate.getId())).thenReturn(upToDate);
		when(domainLookupService.requireOrganization(behind.getId())).thenReturn(behind);
		stubItemSaves();
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(behind.getId()))
				.thenReturn(List.of(menteeOfBehind));

		documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("Pitch deck", "Finansal tablolar"), null);

		verify(notificationService).create(menteeOfBehind, NotificationCategory.TASK_ASSIGNED,
				"Koordinatör belgeler istedi", "Koordinatör Ece şu belgeleri istedi: Finansal tablolar",
				"/startup/files");
		verifyNoMoreInteractions(notificationService);
		verify(appUserRepository, never()).findByOrganizationIdOrderByFullNameAsc(upToDate.getId());
		verify(itemRepository, never()).deleteAll(any());
	}

	@Test
	void replaceOnProgramKeepsOneItemWhenAStartupOwedTheSameTitleTwice() {
		UUID programId = UUID.randomUUID();
		AppUser coordinator = user(UserRole.COORDINATOR, "Koordinatör Ece");
		Program program = new Program();
		program.setId(programId);
		program.setCreatedBy(coordinator.getId());
		Organization startup = organization();
		DocumentRequestItem first = programItem(programId, startup, "Pitch deck", coordinator);
		DocumentRequestItem duplicate = programItem(programId, startup, "Pitch deck", coordinator);
		when(programRepository.findById(programId)).thenReturn(Optional.of(program));
		when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);
		when(itemRepository.findByProgramIdOrderBySortOrderAscCreatedAtAsc(programId))
				.thenReturn(List.of(first, duplicate));
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(programId))
				.thenReturn(List.of(programStartup(programId, startup)));
		when(domainLookupService.requireOrganization(startup.getId())).thenReturn(startup);
		stubItemSaves();

		List<DocumentRequestItemResponse> saved = documentRequestService.replaceOnProgram(
				programId, coordinator.getId(), List.of("Pitch deck"), null);

		assertThat(saved).extracting(DocumentRequestItemResponse::id).containsExactly(first.getId());
		verify(itemRepository).deleteAll(List.of(duplicate));
	}

	@Test
	void saveTitlesOnRequestAddsNothingWhenEveryTitleIsAlreadyOnTheChecklist() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		MentorshipRequest request = mentorshipRequest(mentor, organization());
		DocumentRequestItem earlier = item("Pitch deck", mentor, request.getOrganization());
		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(request.getId()))
				.thenReturn(List.of(earlier));

		documentRequestService.saveTitlesOnRequest(request, mentor, List.of(" Pitch deck ", ""));

		verify(itemRepository, never()).save(any());
		verifyNoInteractions(notificationService, appUserRepository);
	}

	@Test
	void saveTitlesOnMeetingReplacesTheMeetingsChecklistAndTellsTheStartup() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		Meeting meeting = meeting(mentor, organization);
		AppUser mentee = teamMember(organization, "Gülse Buran");
		stubItemSaves();
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee));

		documentRequestService.saveTitlesOnMeeting(meeting, mentor, List.of("Kullanıcı akışı", " Demo videosu "));

		InOrder order = inOrder(itemRepository);
		order.verify(itemRepository).deleteByMeetingId(meeting.getId());
		ArgumentCaptor<DocumentRequestItem> saved = ArgumentCaptor.forClass(DocumentRequestItem.class);
		order.verify(itemRepository, times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(DocumentRequestItem::getTitle)
				.containsExactly("Kullanıcı akışı", "Demo videosu");
		assertThat(saved.getAllValues()).allSatisfy(item -> {
			assertThat(item.getMeeting()).isSameAs(meeting);
			assertThat(item.getMentorshipRequest()).isNull();
			assertThat(item.getOrganization()).isSameAs(organization);
			assertThat(item.getCreatedBy()).isSameAs(mentor);
			assertThat(item.getDueDate()).isNull();
		});
		verify(notificationService).create(mentee, NotificationCategory.TASK_ASSIGNED, "Mentor belgeler istedi",
				"Mentor Ali şu belgeleri istedi: Kullanıcı akışı, Demo videosu",
				"/meetings/research?id=" + meeting.getId());
	}

	@Test
	void saveTitlesOnMeetingLeavesTheChecklistAloneWhenNoTitleIsGiven() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Meeting meeting = meeting(mentor, organization());

		documentRequestService.saveTitlesOnMeeting(meeting, mentor, List.of("  ", ""));
		documentRequestService.saveTitlesOnMeeting(meeting, mentor, List.of());

		verifyNoInteractions(itemRepository, notificationService);
	}

	@Test
	void copyFromRequestToMeetingCarriesOverTheDueDateAndTheFilesAlreadyUploaded() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		AppUser mentee = teamMember(organization, "Gülse Buran");
		MentorshipRequest request = mentorshipRequest(mentor, organization);
		Meeting meeting = meeting(mentor, organization);
		LocalDate dueDate = LocalDate.of(2026, 9, 30);
		DocumentRequestItem stillOwed = item("CV", mentor, organization);
		stillOwed.setMentorshipRequest(request);
		DocumentRequestItem answered = item("Pitch deck", mentor, organization);
		answered.setMentorshipRequest(request);
		answered.setSortOrder(1);
		answered.setDueDate(dueDate);
		StoredDocument file = storedDocument(mentee.getId());
		DocumentRequestUpload sourceUpload = upload(answered, file, mentee);
		when(itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meeting.getId())).thenReturn(List.of());
		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(request.getId()))
				.thenReturn(List.of(stillOwed, answered));
		when(uploadRepository.findByItemIdInOrderByCreatedAtAsc(List.of(stillOwed.getId(), answered.getId())))
				.thenReturn(List.of(sourceUpload));
		stubItemSaves();

		documentRequestService.copyFromRequestToMeeting(request, meeting);

		ArgumentCaptor<DocumentRequestItem> copiedItems = ArgumentCaptor.forClass(DocumentRequestItem.class);
		verify(itemRepository, times(2)).save(copiedItems.capture());
		DocumentRequestItem copyOfAnswered = copiedItems.getAllValues().get(1);
		assertThat(copyOfAnswered.getId()).isNotEqualTo(answered.getId());
		assertThat(copyOfAnswered.getTitle()).isEqualTo("Pitch deck");
		assertThat(copyOfAnswered.getSortOrder()).isEqualTo(1);
		assertThat(copyOfAnswered.getDueDate()).isEqualTo(dueDate);
		assertThat(copyOfAnswered.getCreatedBy()).isSameAs(mentor);
		assertThat(copyOfAnswered.getOrganization()).isSameAs(organization);
		ArgumentCaptor<DocumentRequestUpload> copiedUpload = ArgumentCaptor.forClass(DocumentRequestUpload.class);
		verify(uploadRepository).save(copiedUpload.capture());
		assertThat(copiedUpload.getValue().getId()).isNotEqualTo(sourceUpload.getId());
		assertThat(copiedUpload.getValue().getItem()).isSameAs(copyOfAnswered);
		assertThat(copiedUpload.getValue().getStoredDocument()).isSameAs(file);
		assertThat(copiedUpload.getValue().getUploadedBy()).isSameAs(mentee);
	}

	@Test
	void copyFromRequestToMeetingLeavesAMeetingThatAlreadyHasAChecklistAlone() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		MentorshipRequest request = mentorshipRequest(mentor, organization);
		Meeting meeting = meeting(mentor, organization);
		when(itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meeting.getId()))
				.thenReturn(List.of(item("Kullanıcı akışı", mentor, organization)));

		documentRequestService.copyFromRequestToMeeting(request, meeting);

		verify(itemRepository, never()).save(any());
		verifyNoInteractions(uploadRepository);
	}

	@Test
	void copyFromRequestToMeetingCopiesNothingWhenTheRequestAskedForNothing() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		MentorshipRequest request = mentorshipRequest(mentor, organization);
		Meeting meeting = meeting(mentor, organization);

		documentRequestService.copyFromRequestToMeeting(request, meeting);

		verify(itemRepository).findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(request.getId());
		verify(itemRepository, never()).save(any());
		verifyNoInteractions(uploadRepository);
	}

	@Test
	void copyFromRequestToMeetingIgnoresAMissingRequestOrMeeting() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();

		documentRequestService.copyFromRequestToMeeting(null, meeting(mentor, organization));
		documentRequestService.copyFromRequestToMeeting(mentorshipRequest(mentor, organization), null);

		verifyNoInteractions(itemRepository, uploadRepository);
	}

	@Test
	void attachUploadRecordsTheFileAndTellsTheRequestsMentor() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		DocumentRequestItem item = mentorRequestItem(mentor);
		item.setTitle("Pitch deck");
		AppUser mentee = teamMember(item.getOrganization(), "Gülse Buran");
		StoredDocument file = storedDocument(mentee.getId());

		DocumentRequestUploadResponse response = attach(item, mentee, file);

		ArgumentCaptor<DocumentRequestUpload> saved = ArgumentCaptor.forClass(DocumentRequestUpload.class);
		verify(uploadRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getItem()).isSameAs(item);
		assertThat(saved.getValue().getStoredDocument()).isSameAs(file);
		assertThat(saved.getValue().getUploadedBy()).isSameAs(mentee);
		assertThat(response).isEqualTo(new DocumentRequestUploadResponse(
				saved.getValue().getId(), file.getId(), "pitch-deck.pdf", "application/pdf", 2048, mentee.getId(),
				"Gülse Buran", null));
		verify(notificationService).create(mentor, NotificationCategory.STARTUP_ACTIVITY, "İstenen belge yüklendi",
				"Gülse Buran «Pitch deck» belgesini yükledi.", "/mentorships?tab=requests");
	}

	@Test
	void attachUploadOnAMeetingItemLinksTheMentorsNotificationToTheMeeting() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		mentor.setPreferredLanguage(AppLanguage.EN);
		Organization organization = organization();
		Meeting meeting = meeting(mentor, organization);
		DocumentRequestItem item = item("User flow", mentor, organization);
		item.setMeeting(meeting);
		AppUser mentee = teamMember(organization, "Gülse Buran");

		attach(item, mentee, storedDocument(mentee.getId()));

		verify(notificationService).create(mentor, NotificationCategory.STARTUP_ACTIVITY,
				"Requested document uploaded", "Gülse Buran uploaded the document “User flow”.",
				"/meetings/research?id=" + meeting.getId());
	}

	@Test
	void attachUploadOnAMentorshipItemLinksTheMentorsNotificationToRegularMentorships() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		DocumentRequestItem item = item("Aylık rapor", mentor, organization);
		item.setMentorship(mentorship(mentor, organization));
		AppUser mentee = teamMember(organization, "Gülse Buran");

		attach(item, mentee, storedDocument(mentee.getId()));

		verify(notificationService).create(mentor, NotificationCategory.STARTUP_ACTIVITY, "İstenen belge yüklendi",
				"Gülse Buran «Aylık rapor» belgesini yükledi.", "/mentorships?tab=regular");
	}

	@Test
	void attachUploadOnAProgramsItemSavesTheFileWithoutNotifyingAnyone() {
		Organization organization = organization();
		DocumentRequestItem item = programItem(
				UUID.randomUUID(), organization, "Finansal tablolar", user(UserRole.COORDINATOR, "Koordinatör Ece"));
		AppUser mentee = teamMember(organization, "Gülse Buran");
		StoredDocument file = storedDocument(mentee.getId());

		DocumentRequestUploadResponse response = attach(item, mentee, file);

		assertThat(response.storedDocumentId()).isEqualTo(file.getId());
		verify(uploadRepository).saveAndFlush(any(DocumentRequestUpload.class));
		verifyNoInteractions(notificationService);
	}

	@Test
	void attachUploadRefusesSomeoneOutsideTheItemsStartup() {
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		AppUser otherStartupsMember = teamMember(organization(), "Başka Girişim");
		AppUser withoutAStartup = user(UserRole.COORDINATOR, "Koordinatör Ece");
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(otherStartupsMember.getId())).thenReturn(otherStartupsMember);
		when(domainLookupService.requireUser(withoutAStartup.getId())).thenReturn(withoutAStartup);

		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(otherStartupsMember.getId(), UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("your organization");
		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(withoutAStartup.getId(), UUID.randomUUID())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("your organization");
		verifyNoInteractions(storedDocumentRepository, uploadRepository);
	}

	@Test
	void attachUploadRefusesAFileSomeoneElseUploaded() {
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		AppUser mentee = teamMember(item.getOrganization(), "Gülse Buran");
		StoredDocument teammatesFile = storedDocument(UUID.randomUUID());
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(storedDocumentRepository.findById(teammatesFile.getId())).thenReturn(Optional.of(teammatesFile));

		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(mentee.getId(), teammatesFile.getId())))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("documents you uploaded");
		verifyNoInteractions(uploadRepository, notificationService);
	}

	// Peer mentor evidence has its own visibility rules, so it must not become readable through a checklist.
	@Test
	void attachUploadTreatsPeerMentorEvidenceAsMissing() {
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		AppUser mentee = teamMember(item.getOrganization(), "Gülse Buran");
		StoredDocument evidence = storedDocument(mentee.getId());
		evidence.setKind(DocumentKind.PEER_EVIDENCE);
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(storedDocumentRepository.findById(evidence.getId())).thenReturn(Optional.of(evidence));

		assertThatThrownBy(() -> documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(mentee.getId(), evidence.getId())))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(uploadRepository, notificationService);
	}

	@Test
	void attachUploadReportsAnUnknownItem() {
		UUID itemId = UUID.randomUUID();
		when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentRequestService.attachUpload(itemId,
				new AttachDocumentRequestUploadRequest(UUID.randomUUID(), UUID.randomUUID())))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(itemId.toString());
		verifyNoInteractions(uploadRepository);
	}

	@Test
	void deleteUploadLetsTheUploaderRemoveTheirFile() {
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		AppUser mentee = teamMember(item.getOrganization(), "Gülse Buran");
		DocumentRequestUpload upload = upload(item, storedDocument(mentee.getId()), mentee);
		when(uploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);

		documentRequestService.deleteUpload(upload.getId(), mentee.getId());

		verify(uploadRepository).delete(upload);
	}

	@Test
	void deleteUploadLetsTheRequestsMentorRemoveAStartupsFile() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");

		assertThatTheItemsMentorCanDelete(mentorRequestItem(mentor), mentor);
	}

	@Test
	void deleteUploadLetsTheMeetingsMentorRemoveAStartupsFile() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		DocumentRequestItem item = item("Kullanıcı akışı", mentor, organization);
		item.setMeeting(meeting(mentor, organization));

		assertThatTheItemsMentorCanDelete(item, mentor);
	}

	@Test
	void deleteUploadLetsTheMentorshipsMentorRemoveAStartupsFile() {
		AppUser mentor = user(UserRole.MENTOR, "Mentor Ali");
		Organization organization = organization();
		DocumentRequestItem item = item("Aylık rapor", mentor, organization);
		item.setMentorship(mentorship(mentor, organization));

		assertThatTheItemsMentorCanDelete(item, mentor);
	}

	@Test
	void deleteUploadRefusesATeammateWhoDidNotUploadTheFile() {
		DocumentRequestItem item = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		AppUser uploader = teamMember(item.getOrganization(), "Gülse Buran");
		AppUser teammate = teamMember(item.getOrganization(), "Sude Arya");
		DocumentRequestUpload upload = upload(item, storedDocument(uploader.getId()), uploader);
		when(uploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));
		when(domainLookupService.requireUser(teammate.getId())).thenReturn(teammate);

		assertThatThrownBy(() -> documentRequestService.deleteUpload(upload.getId(), teammate.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("uploader or mentor");
		verify(uploadRepository, never()).delete(any());
	}

	@Test
	void deleteUploadRefusesAMentorWhoIsNotTheItemsMentor() {
		AppUser stranger = user(UserRole.MENTOR, "Başka Mentor");
		Organization organization = organization();
		AppUser uploader = teamMember(organization, "Gülse Buran");
		DocumentRequestItem ofAnotherMentor = mentorRequestItem(user(UserRole.MENTOR, "Mentor Ali"));
		DocumentRequestItem ofAProgram = programItem(
				UUID.randomUUID(), organization, "Finansal tablolar", user(UserRole.COORDINATOR, "Koordinatör Ece"));
		DocumentRequestItem ofAMeetingWithoutAMentor = item("Kullanıcı akışı", uploader, organization);
		ofAMeetingWithoutAMentor.setMeeting(meeting(null, organization));
		when(domainLookupService.requireUser(stranger.getId())).thenReturn(stranger);

		for (DocumentRequestItem item : List.of(ofAnotherMentor, ofAProgram, ofAMeetingWithoutAMentor)) {
			DocumentRequestUpload upload = upload(item, storedDocument(uploader.getId()), uploader);
			when(uploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));

			assertThatThrownBy(() -> documentRequestService.deleteUpload(upload.getId(), stranger.getId()))
					.isInstanceOf(InvalidRequestException.class);
		}
		verify(uploadRepository, never()).delete(any());
	}

	@Test
	void deleteUploadReportsAnUnknownUpload() {
		UUID uploadId = UUID.randomUUID();
		when(uploadRepository.findById(uploadId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentRequestService.deleteUpload(uploadId, UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(uploadId.toString());
		verify(uploadRepository, never()).delete(any());
	}

	private void assertThatTheItemsMentorCanDelete(DocumentRequestItem item, AppUser mentor) {
		AppUser mentee = teamMember(item.getOrganization(), "Gülse Buran");
		DocumentRequestUpload upload = upload(item, storedDocument(mentee.getId()), mentee);
		when(uploadRepository.findById(upload.getId())).thenReturn(Optional.of(upload));
		when(domainLookupService.requireUser(mentor.getId())).thenReturn(mentor);

		documentRequestService.deleteUpload(upload.getId(), mentor.getId());

		verify(uploadRepository).delete(upload);
	}

	private DocumentRequestUploadResponse attach(DocumentRequestItem item, AppUser actor, StoredDocument file) {
		when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
		when(domainLookupService.requireUser(actor.getId())).thenReturn(actor);
		when(storedDocumentRepository.findById(file.getId())).thenReturn(Optional.of(file));
		when(uploadRepository.saveAndFlush(any(DocumentRequestUpload.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		return documentRequestService.attachUpload(item.getId(),
				new AttachDocumentRequestUploadRequest(actor.getId(), file.getId()));
	}

	private void stubItemSaves() {
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private static AppUser teamMember(Organization organization, String fullName) {
		AppUser member = user(UserRole.MENTEE, fullName);
		member.setOrganization(organization);
		return member;
	}

	private static MentorshipRequest mentorshipRequest(AppUser mentor, Organization organization) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(UUID.randomUUID());
		request.setMentor(mentor);
		request.setOrganization(organization);
		return request;
	}

	private static Meeting meeting(AppUser mentor, Organization organization) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setOrganization(organization);
		return meeting;
	}

	private static Mentorship mentorship(AppUser mentor, Organization organization) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		return mentorship;
	}

	private static DocumentRequestItem item(String title, AppUser createdBy, Organization organization) {
		DocumentRequestItem item = new DocumentRequestItem();
		item.setId(UUID.randomUUID());
		item.setTitle(title);
		item.setCreatedBy(createdBy);
		item.setOrganization(organization);
		return item;
	}

	private static StoredDocument storedDocument(UUID ownerId) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(ownerId);
		document.setKind(DocumentKind.REQUESTED_DOC);
		document.setOriginalFilename("pitch-deck.pdf");
		document.setContentType("application/pdf");
		document.setSizeBytes(2048);
		return document;
	}

	private static DocumentRequestUpload upload(DocumentRequestItem item, StoredDocument file, AppUser uploadedBy) {
		DocumentRequestUpload upload = new DocumentRequestUpload();
		upload.setId(UUID.randomUUID());
		upload.setItem(item);
		upload.setStoredDocument(file);
		upload.setUploadedBy(uploadedBy);
		return upload;
	}
}
