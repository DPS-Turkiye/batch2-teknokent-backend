package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.domain.StartupStageState;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.CreateStartupRoundRequest;
import com.dps.teknokent.dto.requests.SubmitProcessAssignmentRequest;
import com.dps.teknokent.dto.requests.UpdateStartupStageRequest;
import com.dps.teknokent.dto.responses.StartupStageResponse;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.StartupRound;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.StartupRoundRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

@ExtendWith(MockitoExtension.class)
class StartupProcessServiceTest {

    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FOUNDER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MENTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID COORDINATOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID TEAMMATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ADMIN_ID = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID PROGRAM_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Mock
    private DomainLookupService domainLookupService;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private StartupRoundRepository startupRoundRepository;

    @Mock
    private ActionItemRepository actionItemRepository;

    @Mock
    private StoredDocumentRepository storedDocumentRepository;

    @Mock
    private MentorshipRequestRepository mentorshipRequestRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProgramRepository programRepository;

    @Mock
    private ProgramCoordinatorRepository programCoordinatorRepository;

    @Mock
    private ProgramStartupRepository programStartupRepository;

    @InjectMocks
    private StartupProcessService startupProcessService;

    @Test
    void stages_marksEarlierStagesCompletedAndLaterOnesUpcoming() {
        List<StartupStageResponse> stages = StartupProcessService.stages(StartupStage.MVP);

        assertThat(stages).extracting(StartupStageResponse::stage).containsExactly(StartupStage.values());
        assertThat(stages).extracting(StartupStageResponse::state).containsExactly(
                StartupStageState.COMPLETED, StartupStageState.COMPLETED, StartupStageState.CURRENT,
                StartupStageState.UPCOMING, StartupStageState.UPCOMING, StartupStageState.UPCOMING,
                StartupStageState.UPCOMING);
    }

    @Test
    void stages_areAllUpcomingBeforeAStageIsSet() {
        assertThat(StartupProcessService.stages(null))
                .extracting(StartupStageResponse::state)
                .containsOnly(StartupStageState.UPCOMING);
    }

    @Test
    void pickCurrentRound_prefersTheRoundRunningToday() {
        StartupRound past = round("Geçmiş", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        StartupRound running = round("MVP test turu", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31));
        StartupRound next = round("Pazar turu", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(StartupProcessService.pickCurrentRound(List.of(next, running, past), TODAY)).isSameAs(running);
    }

    @Test
    void pickCurrentRound_fallsBackToTheNextPlannedRound() {
        StartupRound past = round("Geçmiş", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        StartupRound later = round("Finans turu", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        StartupRound next = round("Pazar turu", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(StartupProcessService.pickCurrentRound(List.of(later, next, past), TODAY)).isSameAs(next);
    }

    @Test
    void pickCurrentRound_fallsBackToTheLastRoundHeld() {
        StartupRound older = round("Problem turu", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        StartupRound recent = round("Çözüm turu", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(StartupProcessService.pickCurrentRound(List.of(recent, older), TODAY)).isSameAs(recent);
        assertThat(StartupProcessService.pickCurrentRound(List.of(), TODAY)).isNull();
    }

    @Test
    void describe_listsOnlyAssignmentsOldestFirstWithTheirSubmittedFile() {
        Organization organization = startup();
        ActionItem plainActionItem = assignment("Toplantı notlarını paylaş", null);
        ActionItem finance = assignment("Finansal model", StartupStage.FINANCE);
        ActionItem market = assignment("Acquisition kanalı", StartupStage.MARKET);
        UUID documentId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        market.setSubmissionDocumentId(documentId);
        StoredDocument document = new StoredDocument();
        document.setId(documentId);
        document.setOriginalFilename("kanal-analizi.pdf");
        when(actionItemRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(List.of(market, plainActionItem, finance));
        when(storedDocumentRepository.findAllById(Set.of(documentId))).thenReturn(List.of(document));

        var process = startupProcessService.describe(organization, TODAY);

        assertThat(process.assignments()).extracting("title").containsExactly("Finansal model", "Acquisition kanalı");
        assertThat(process.assignments().get(1).submissionFilename()).isEqualTo("kanal-analizi.pdf");
        assertThat(process.currentStage()).isEqualTo(StartupStage.MVP);
    }

    @Test
    void updateStage_letsAnAdmin() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN, null));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));

        var process = startupProcessService.updateStage(ORG_ID,
                new UpdateStartupStageRequest(ADMIN_ID, StartupStage.BUSINESS_MODEL));

        assertThat(process.currentStage()).isEqualTo(StartupStage.BUSINESS_MODEL);
        assertThat(organization.getCurrentStage()).isEqualTo(StartupStage.BUSINESS_MODEL);
    }

    @Test
    void updateStage_letsACoordinatorOfTheStartupsProgram() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(COORDINATOR_ID))
                .thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
        programWithCoordinator(true);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));

        var process = startupProcessService.updateStage(ORG_ID,
                new UpdateStartupStageRequest(COORDINATOR_ID, StartupStage.PITCH));

        assertThat(process.currentStage()).isEqualTo(StartupStage.PITCH);
    }

    @Test
    void updateStage_rejectsACoordinatorOfAnotherProgram() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(COORDINATOR_ID))
                .thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR, null));
        programWithCoordinator(false);

        assertThatThrownBy(() -> startupProcessService.updateStage(ORG_ID,
                new UpdateStartupStageRequest(COORDINATOR_ID, StartupStage.PITCH)))
                .isInstanceOf(InvalidRequestException.class);

        verify(organizationRepository, never()).save(any(Organization.class));
    }

    @Test
    void updateStage_leavesTheFounderAndMentorsReadOnly() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
        when(domainLookupService.requireUser(TEAMMATE_ID))
                .thenReturn(user(TEAMMATE_ID, UserRole.MENTEE, organization));

        for (UUID actorId : List.of(FOUNDER_ID, MENTOR_ID, TEAMMATE_ID)) {
            assertThatThrownBy(() -> startupProcessService.updateStage(ORG_ID,
                    new UpdateStartupStageRequest(actorId, StartupStage.MARKET)))
                    .isInstanceOf(InvalidRequestException.class);
        }

        verify(organizationRepository, never()).save(any(Organization.class));
    }

    private void programWithCoordinator(boolean coordinates) {
        Program program = new Program();
        program.setId(PROGRAM_ID);
        program.setCreatedBy(UUID.randomUUID());
        ProgramStartup link = new ProgramStartup();
        link.setProgramId(PROGRAM_ID);
        link.setOrganizationId(ORG_ID);
        when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(link));
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(programCoordinatorRepository.existsByProgramIdAndUserId(PROGRAM_ID, COORDINATOR_ID))
                .thenReturn(coordinates);
    }

    @Test
    void createRound_rejectsARoundThatEndsBeforeItStarts() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());

        assertThatThrownBy(() -> startupProcessService.createRound(ORG_ID, new CreateStartupRoundRequest(
                FOUNDER_ID, "Value proposition & onboarding", "MVP test turu", StartupStage.MVP,
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 2))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("end before it starts");

        verify(startupRoundRepository, never()).saveAndFlush(any(StartupRound.class));
    }

    @Test
    void createRound_storesTheRoundForAnEditor() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        ArgumentCaptor<StartupRound> saved = ArgumentCaptor.forClass(StartupRound.class);

        startupProcessService.createRound(ORG_ID, new CreateStartupRoundRequest(
                FOUNDER_ID, "Value proposition & onboarding", "MVP test turu", StartupStage.MVP,
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31)));

        verify(startupRoundRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("MVP test turu");
        assertThat(saved.getValue().getCreatedBy()).isSameAs(organization.getFounder());
    }

    @Test
    void createAssignment_takesTheStageFromTheRoundAndGivesItToTheFounder() {
        Organization organization = startup();
        StartupRound round = round("MVP test turu", LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 31));
        round.setStage(StartupStage.MVP);
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        when(startupRoundRepository.findByIdAndOrganizationId(round.getId(), ORG_ID)).thenReturn(Optional.of(round));
        ArgumentCaptor<ActionItem> saved = ArgumentCaptor.forClass(ActionItem.class);

        startupProcessService.createAssignment(ORG_ID, new CreateProcessAssignmentRequest(
                FOUNDER_ID, round.getId(), null, "Kullanıcı testi raporu", null, null));

        verify(actionItemRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStage()).isEqualTo(StartupStage.MVP);
        assertThat(saved.getValue().getRound()).isSameAs(round);
        assertThat(saved.getValue().getOwner()).isSameAs(organization.getFounder());
        assertThat(saved.getValue().getCreatedBy()).isSameAs(organization.getFounder());
        assertThat(saved.getValue().getStatus()).isEqualTo(ActionItemStatus.OPEN);
        verify(notificationService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void createAssignment_notifiesTheFounderWhenAMentorAddsIt() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(user(MENTOR_ID, UserRole.MENTOR, null));
        when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                MENTOR_ID, ORG_ID, MentorshipRequestStatus.ACCEPTED))
                .thenReturn(Optional.of(new MentorshipRequest()));

        startupProcessService.createAssignment(ORG_ID, new CreateProcessAssignmentRequest(
                MENTOR_ID, null, StartupStage.FINANCE, "Finansal model", null, null));

        verify(notificationService).create(
                eq(organization.getFounder()),
                eq(NotificationCategory.TASK_ASSIGNED),
                eq("Yeni görev"),
                eq("Demo MENTOR yeni bir görev ekledi: Finansal model"),
                eq("/startup/process"));
    }

    @Test
    void createAssignment_rejectsOneWithNeitherStageNorRound() {
        Organization organization = startup();
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());

        assertThatThrownBy(() -> startupProcessService.createAssignment(ORG_ID, new CreateProcessAssignmentRequest(
                FOUNDER_ID, null, null, "Başlıksız", null, null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void submitAssignment_linksTheFileAndCompletesTheAssignment() {
        Organization organization = startup();
        ActionItem finance = assignment("Finansal model", StartupStage.FINANCE);
        StoredDocument document = document(ORG_ID);
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAMMATE_ID))
                .thenReturn(user(TEAMMATE_ID, UserRole.MENTEE, organization));
        when(actionItemRepository.findByIdAndOrganizationId(finance.getId(), ORG_ID)).thenReturn(Optional.of(finance));
        when(storedDocumentRepository.findById(document.getId())).thenReturn(Optional.of(document));

        startupProcessService.submitAssignment(ORG_ID, finance.getId(),
                new SubmitProcessAssignmentRequest(TEAMMATE_ID, document.getId()));

        assertThat(finance.getSubmissionDocumentId()).isEqualTo(document.getId());
        assertThat(finance.getStatus()).isEqualTo(ActionItemStatus.COMPLETED);
        verify(actionItemRepository).saveAndFlush(finance);
    }

    @Test
    void submitAssignment_rejectsAFileFromAnotherStartup() {
        Organization organization = startup();
        ActionItem finance = assignment("Finansal model", StartupStage.FINANCE);
        StoredDocument foreign = document(UUID.fromString("10000000-0000-0000-0000-000000000999"));
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAMMATE_ID))
                .thenReturn(user(TEAMMATE_ID, UserRole.MENTEE, organization));
        when(actionItemRepository.findByIdAndOrganizationId(finance.getId(), ORG_ID)).thenReturn(Optional.of(finance));
        when(storedDocumentRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> startupProcessService.submitAssignment(ORG_ID, finance.getId(),
                new SubmitProcessAssignmentRequest(TEAMMATE_ID, foreign.getId())))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(finance.getStatus()).isEqualTo(ActionItemStatus.OPEN);
    }

    @Test
    void submitAssignment_refusesAPlainActionItem() {
        Organization organization = startup();
        ActionItem plain = assignment("Toplantı notlarını paylaş", null);
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAMMATE_ID))
                .thenReturn(user(TEAMMATE_ID, UserRole.MENTEE, organization));
        when(actionItemRepository.findByIdAndOrganizationId(plain.getId(), ORG_ID)).thenReturn(Optional.of(plain));

        assertThatThrownBy(() -> startupProcessService.submitAssignment(ORG_ID, plain.getId(),
                new SubmitProcessAssignmentRequest(TEAMMATE_ID, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Organization startup() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        organization.setName("Döngü");
        organization.setCurrentStage(StartupStage.MVP);
        organization.setFounder(user(FOUNDER_ID, UserRole.MENTEE, organization));
        return organization;
    }

    private static AppUser user(UUID id, UserRole role, Organization organization) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setFullName("Demo " + role);
        user.setRole(role);
        user.setOrganization(organization);
        return user;
    }

    private static StartupRound round(String name, LocalDate startsOn, LocalDate endsOn) {
        StartupRound round = new StartupRound();
        round.setId(UUID.randomUUID());
        round.setTitle("Value proposition & onboarding");
        round.setName(name);
        round.setStage(StartupStage.MVP);
        round.setStartsOn(startsOn);
        round.setEndsOn(endsOn);
        return round;
    }

    private static ActionItem assignment(String title, StartupStage stage) {
        ActionItem item = new ActionItem();
        item.setId(UUID.randomUUID());
        item.setTitle(title);
        item.setStage(stage);
        item.setStatus(ActionItemStatus.OPEN);
        item.setCreatedAt(Instant.parse("2026-08-10T09:00:00Z"));
        return item;
    }

    private static StoredDocument document(UUID organizationId) {
        StoredDocument document = new StoredDocument();
        document.setId(UUID.randomUUID());
        document.setOrganizationId(organizationId);
        document.setOriginalFilename("finansal-model.pdf");
        return document;
    }

    @Test
    void get_refusesSomeoneOutsideTheStartup() {
        UUID outsider = UUID.fromString("90000000-0000-0000-0000-000000000001");
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(startup());
        when(domainLookupService.requireUser(outsider)).thenReturn(user(outsider, UserRole.MENTEE, null));

        assertThatThrownBy(() -> startupProcessService.get(ORG_ID, outsider))
                .isInstanceOf(InvalidRequestException.class);
    }
}
