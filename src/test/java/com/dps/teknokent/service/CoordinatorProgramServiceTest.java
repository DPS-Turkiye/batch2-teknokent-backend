package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateProgramEventRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.UpdateProgramDetailsRequest;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.service.CoordinatorProgramService.HealthBucket;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class CoordinatorProgramServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramPhaseRepository programPhaseRepository;
	@Mock
	private ProgramEventRepository programEventRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private BottleneckRepository bottleneckRepository;
	@Mock
	private ActionItemRepository actionItemRepository;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MeetingNoteRepository meetingNoteRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@InjectMocks
	private CoordinatorProgramService service;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID MENTEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private static final UUID MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID PENDING_MENTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000016");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID TERRA_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID LUMEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID KAVUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);

	@Test
	void classifiesByDaysSinceContactThenByOpenIssues() {
		assertThat(CoordinatorProgramService.classify(14, false, 14, 14)).isEqualTo(HealthBucket.RISKY);
		assertThat(CoordinatorProgramService.classify(13, false, 14, 14)).isEqualTo(HealthBucket.HEALTHY);
		assertThat(CoordinatorProgramService.classify(13, true, 14, 14)).isEqualTo(HealthBucket.FOLLOW_UP);
		assertThat(CoordinatorProgramService.classify(10, false, 7, 21)).isEqualTo(HealthBucket.FOLLOW_UP);
		assertThat(CoordinatorProgramService.classify(21, false, 7, 21)).isEqualTo(HealthBucket.RISKY);
	}

	@Test
	void aCoordinatorListsOnlyTheirOwnProgramsWithCounts() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		Program program = program(COORDINATOR_ID);
		program.setStartDate(today.minusDays(35));
		program.setEndDate(today.minusDays(35).plusDays(111));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 4)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED),
						membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING)),
				List.of(phase("Mentorluk dönemi", today.minusDays(5), today.plusDays(30))));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(3))),
				List.of(), List.of(), List.of());

		var programs = service.list(COORDINATOR_ID);

		assertThat(programs).hasSize(1);
		var summary = programs.getFirst();
		assertThat(summary.lifecycle()).isEqualTo(ProgramLifecycle.ACTIVE);
		assertThat(summary.coordinatorName()).isEqualTo("Elif Demir");
		assertThat(summary.teamCount()).isEqualTo(2);
		assertThat(summary.mentorCount()).isEqualTo(1);
		assertThat(summary.pendingMentorCount()).isEqualTo(1);
		assertThat(summary.activeMentorshipCount()).isEqualTo(1);
		assertThat(summary.awaitingMatchCount()).isEqualTo(1);
		assertThat(summary.oldestAwaitingMatchDays()).isEqualTo(4);
		assertThat(summary.riskyMentorshipCount()).isZero();
		assertThat(summary.currentPhase().name()).isEqualTo("Mentorluk dönemi");
		assertThat(summary.currentWeek()).isEqualTo(6);
		assertThat(summary.totalWeeks()).isEqualTo(16);
		verify(programRepository, never()).findAllByOrderByCreatedAtDesc();
	}

	@Test
	void anAdminListsEveryProgram() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN));
		when(programRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

		assertThat(service.list(ADMIN_ID)).isEmpty();

		verify(programRepository, never()).findByCreatedByOrderByCreatedAtDesc(any());
	}

	@Test
	void aMenteeCannotListPrograms() {
		when(domainLookupService.requireUser(MENTEE_ID)).thenReturn(user(MENTEE_ID, UserRole.MENTEE));

		assertThatThrownBy(() -> service.list(MENTEE_ID)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aCoordinatorCannotOpenAnotherCoordinatorsProgram() {
		when(domainLookupService.requireUser(OTHER_COORDINATOR_ID))
				.thenReturn(user(OTHER_COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program(COORDINATOR_ID)));

		assertThatThrownBy(() -> service.detail(OTHER_COORDINATOR_ID, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theDetailSortsTeamsByHealthAndReportsWhatIsRisky() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1), startup(KAVUN_ID, 6)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED)),
				List.of());
		Mentorship terra = mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		Mentorship lumen = mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"), organization(KAVUN_ID, "Kavun")),
				List.of(terra, lumen),
				List.of(meeting(TERRA_ID, 20), meeting(LUMEN_ID, 2)),
				List.of(bottleneck(TERRA_ID, "Pazarlama stratejisi")),
				List.of(actionItem(LUMEN_ID)));
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());
		when(mentorshipRepository.countOrganizationsByMentor(anyCollection(), anyCollection()))
				.thenReturn(List.of(count(MENTOR_ID, 2)));
		MentorProfile profile = new MentorProfile();
		profile.setId(MENTOR_ID);
		profile.setMenteeCapacity(2);
		when(mentorProfileRepository.findAllById(any())).thenReturn(List.of(profile));

		var detail = service.detail(COORDINATOR_ID, PROGRAM_ID);

		assertThat(detail.health().risky()).isEqualTo(1);
		assertThat(detail.health().followUp()).isEqualTo(1);
		assertThat(detail.health().healthy()).isZero();
		assertThat(detail.health().riskyTopics())
				.extracting(topic -> topic.title() + ":" + topic.teams())
				.containsExactly("Pazarlama stratejisi:1");
		assertThat(detail.awaitingTeams()).singleElement().satisfies(team -> {
			assertThat(team.name()).isEqualTo("Kavun");
			assertThat(team.waitingDays()).isEqualTo(6);
		});
		assertThat(detail.teams().mentored()).isEqualTo(2);
		assertThat(detail.teams().awaitingMatch()).isEqualTo(1);
		assertThat(detail.mentors().full()).isEqualTo(1);
		assertThat(detail.program().riskyMentorshipCount()).isEqualTo(1);
		assertThat(detail.rules().meetingFrequencyDays()).isEqualTo(14);
	}

	@Test
	void replacingPhasesDeletesAndFlushesBeforeSavingInOrder() {
		stubManagedProgram(program(COORDINATOR_ID));
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		service.replacePhases(COORDINATOR_ID, PROGRAM_ID, new ReplaceProgramPhasesRequest(null, List.of(
				new ReplaceProgramPhasesRequest.PhaseInput("  Başvuru ", today, today.plusDays(10)),
				new ReplaceProgramPhasesRequest.PhaseInput("Demo Day", null, null))));

		InOrder order = inOrder(programPhaseRepository);
		order.verify(programPhaseRepository).deleteByProgramId(PROGRAM_ID);
		order.verify(programPhaseRepository).flush();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ProgramPhase>> saved = ArgumentCaptor.forClass(List.class);
		order.verify(programPhaseRepository).saveAll(saved.capture());
		assertThat(saved.getValue())
				.extracting(phase -> phase.getPosition() + ":" + phase.getName())
				.containsExactly("0:Başvuru", "1:Demo Day");
	}

	@Test
	void aPhaseCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));

		assertThatThrownBy(() -> service.replacePhases(COORDINATOR_ID, PROGRAM_ID, new ReplaceProgramPhasesRequest(
				null, List.of(new ReplaceProgramPhasesRequest.PhaseInput("Seçim", today, today.minusDays(1))))))
				.isInstanceOf(InvalidRequestException.class);
		verify(programPhaseRepository, never()).deleteByProgramId(any());
	}

	@Test
	void updatingDetailsKeepsRulesThatWereNotSent() {
		Program program = program(COORDINATOR_ID);
		program.setStaleAfterDays(21);
		stubManagedProgram(program);
		stubProgramRows(List.of(), List.of(), List.of());
		when(programEventRepository.findByProgramIdInAndStartsAtGreaterThanEqualOrderByStartsAtAsc(anyList(), any()))
				.thenReturn(List.of());

		var detail = service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, " METU Stars 2026 ", "  ", today, today.plusDays(100), 10, null, false, null));

		assertThat(program.getName()).isEqualTo("METU Stars 2026");
		assertThat(program.getDescription()).isNull();
		assertThat(detail.rules().meetingFrequencyDays()).isEqualTo(10);
		assertThat(detail.rules().staleAfterDays()).isEqualTo(21);
		assertThat(detail.rules().meetingNoteRequired()).isFalse();
		assertThat(detail.rules().autoMatchSuggestions()).isTrue();
		verify(programRepository).save(program);
	}

	@Test
	void aProgramCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));

		assertThatThrownBy(() -> service.updateDetails(COORDINATOR_ID, PROGRAM_ID, new UpdateProgramDetailsRequest(
				null, "METU Stars", null, today, today.minusDays(1), null, null, null, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programRepository, never()).save(any());
	}

	@Test
	void createsAnEventForTheProgram() {
		stubManagedProgram(program(COORDINATOR_ID));
		when(programEventRepository.save(any(ProgramEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Instant startsAt = Instant.parse("2026-10-17T20:59:00Z");

		var event = service.createEvent(COORDINATOR_ID, PROGRAM_ID, new CreateProgramEventRequest(
				null, " Ara Rapor Teslimi ", ProgramEventKind.DEADLINE, startsAt, null, null));

		assertThat(event.title()).isEqualTo("Ara Rapor Teslimi");
		assertThat(event.kind()).isEqualTo(ProgramEventKind.DEADLINE);
		assertThat(event.programId()).isEqualTo(PROGRAM_ID);
	}

	@Test
	void anEventCannotEndBeforeItStarts() {
		stubManagedProgram(program(COORDINATOR_ID));
		Instant startsAt = Instant.parse("2026-10-17T10:00:00Z");

		assertThatThrownBy(() -> service.createEvent(COORDINATOR_ID, PROGRAM_ID, new CreateProgramEventRequest(
				null, "Atölye", ProgramEventKind.EVENT, startsAt, startsAt.minusSeconds(60), null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(programEventRepository, never()).save(any());
	}

	@Test
	void deletingAnEventOfAnotherProgramIsNotFound() {
		stubManagedProgram(program(COORDINATOR_ID));
		UUID eventId = UUID.randomUUID();
		when(programEventRepository.findByIdAndProgramId(eventId, PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteEvent(COORDINATOR_ID, PROGRAM_ID, eventId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void theHomeSumsUpTheWeekAndWhatNeedsADecision() {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findByCreatedByOrderByCreatedAtDesc(COORDINATOR_ID)).thenReturn(List.of(program(COORDINATOR_ID)));
		stubProgramRows(
				List.of(startup(TERRA_ID, 1), startup(LUMEN_ID, 1), startup(KAVUN_ID, 6)),
				List.of(membership(MENTOR_ID, ProgramMentorStatus.ACCEPTED),
						membership(PENDING_MENTOR_ID, ProgramMentorStatus.PENDING)),
				List.of());
		Meeting terraPast = meeting(TERRA_ID, 20);
		Meeting lumenRecent = meeting(LUMEN_ID, 3);
		lumenRecent.setId(UUID.randomUUID());
		Meeting terraNext = meeting(TERRA_ID, 0);
		terraNext.setId(UUID.randomUUID());
		terraNext.setStatus(MeetingStatus.SCHEDULED);
		terraNext.setScheduledAt(Instant.now().plus(2, ChronoUnit.DAYS));
		stubTeamData(
				List.of(organization(TERRA_ID, "Terra"), organization(LUMEN_ID, "Lumen"), organization(KAVUN_ID, "Kavun")),
				List.of(mentorship(TERRA_ID, MentorshipStatus.ACTIVE, today.minusDays(40)),
						mentorship(LUMEN_ID, MentorshipStatus.ACTIVE, today.minusDays(40))),
				List.of(terraNext, lumenRecent, terraPast),
				List.of(bottleneck(TERRA_ID, "Pazarlama stratejisi")),
				List.of(actionItem(LUMEN_ID)));
		when(appUserRepository.findAllById(any())).thenReturn(List.of(
				user(COORDINATOR_ID, UserRole.COORDINATOR), user(PENDING_MENTOR_ID, UserRole.MENTOR)));
		MentorshipRequest slow = new MentorshipRequest();
		slow.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		slow.setCreatedAt(Instant.now().minus(5, ChronoUnit.DAYS));
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(anyCollection(), eq(MentorshipRequestStatus.PENDING)))
				.thenReturn(List.of(slow));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(anyCollection(), any(), any()))
				.thenReturn(List.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBetween(
				anyCollection(), anyCollection(), any(), any()))
				.thenReturn(List.of());
		when(meetingNoteRepository.findByMeetingIdIn(anyCollection())).thenReturn(List.of());

		var home = service.overview(COORDINATOR_ID, null);

		assertThat(home.programs()).extracting(option -> option.name()).containsExactly("METU Stars 2026");
		var metrics = home.metrics();
		assertThat(metrics.activeMentorships()).isEqualTo(2);
		assertThat(metrics.mentoredTeams()).isEqualTo(2);
		assertThat(metrics.activeMentors()).isEqualTo(1);
		assertThat(metrics.staleMentorships()).isEqualTo(1);
		assertThat(metrics.overdueActions()).isEqualTo(1);
		assertThat(metrics.openBottlenecks()).isEqualTo(1);
		assertThat(metrics.slowMentors()).isEqualTo(1);
		assertThat(home.nextMeeting().meetingId()).isEqualTo(terraNext.getId());
		assertThat(home.nextMeeting().sequence()).isEqualTo(2);
		assertThat(home.nextMeeting().title()).isEqualTo("Mentorluk görüşmesi");
		assertThat(home.agenda()).singleElement()
				.satisfies(item -> assertThat(item.kind()).isEqualTo(CoordinatorAgendaKind.MEETING));
		assertThat(home.actions().awaitingTeams()).singleElement()
				.satisfies(team -> assertThat(team.name()).isEqualTo("Kavun"));
		assertThat(home.actions().pendingMentors()).singleElement()
				.satisfies(mentor -> assertThat(mentor.mentorId()).isEqualTo(PENDING_MENTOR_ID));
		assertThat(home.actions().missingNotes()).singleElement()
				.satisfies(note -> assertThat(note.meetingId()).isEqualTo(lumenRecent.getId()));
	}

	private void stubManagedProgram(Program program) {
		when(domainLookupService.requireUser(COORDINATOR_ID)).thenReturn(user(COORDINATOR_ID, UserRole.COORDINATOR));
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
	}

	private void stubProgramRows(List<ProgramStartup> startups, List<ProgramMentor> mentors, List<ProgramPhase> phases) {
		when(appUserRepository.findAllById(any())).thenReturn(List.of(user(COORDINATOR_ID, UserRole.COORDINATOR)));
		when(programPhaseRepository.findByProgramIdInOrderByPositionAsc(anyList())).thenReturn(phases);
		when(programStartupRepository.findByProgramIdIn(anyList())).thenReturn(startups);
		when(programMentorRepository.findByProgramIdIn(anyList())).thenReturn(mentors);
	}

	private void stubTeamData(
			List<Organization> organizations,
			List<Mentorship> mentorships,
			List<Meeting> meetings,
			List<Bottleneck> bottlenecks,
			List<ActionItem> overdue) {
		when(organizationRepository.findAllById(any())).thenReturn(organizations);
		when(mentorshipRepository.findByOrganizationIdIn(anyCollection())).thenReturn(mentorships);
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(anyCollection())).thenReturn(meetings);
		when(bottleneckRepository.findByOrganizationIdInAndStatusIn(anyCollection(), anyCollection()))
				.thenReturn(bottlenecks);
		when(actionItemRepository.findByOrganizationIdInAndStatusNotInAndDueDateBefore(
				anyCollection(), anyCollection(), any()))
				.thenReturn(overdue);
	}

	private Program program(UUID coordinatorId) {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(coordinatorId);
		program.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
		return program;
	}

	private ProgramStartup startup(UUID organizationId, int daysAgo) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(PROGRAM_ID);
		startup.setOrganizationId(organizationId);
		startup.setCreatedAt(today.minusDays(daysAgo).atTime(12, 0).atZone(MeetingSlotTimes.APP_ZONE).toInstant());
		return startup;
	}

	private static ProgramMentor membership(UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(PROGRAM_ID);
		membership.setMentorId(mentorId);
		membership.setStatus(status);
		return membership;
	}

	private static ProgramPhase phase(String name, LocalDate start, LocalDate end) {
		ProgramPhase phase = new ProgramPhase();
		phase.setId(UUID.randomUUID());
		phase.setProgramId(PROGRAM_ID);
		phase.setName(name);
		phase.setStartDate(start);
		phase.setEndDate(end);
		return phase;
	}

	private static Organization organization(UUID id, String name) {
		Organization organization = new Organization();
		organization.setId(id);
		organization.setName(name);
		return organization;
	}

	private static Mentorship mentorship(UUID organizationId, MentorshipStatus status, LocalDate startDate) {
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		mentorship.setOrganization(organization(organizationId, "Takım"));
		mentorship.setStatus(status);
		mentorship.setStartDate(startDate);
		return mentorship;
	}

	private static Meeting meeting(UUID organizationId, int daysAgo) {
		Meeting meeting = new Meeting();
		meeting.setMentor(user(MENTOR_ID, UserRole.MENTOR));
		meeting.setOrganization(organization(organizationId, "Takım"));
		meeting.setStatus(MeetingStatus.COMPLETED);
		meeting.setScheduledAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
		return meeting;
	}

	private static Bottleneck bottleneck(UUID organizationId, String title) {
		Bottleneck bottleneck = new Bottleneck();
		bottleneck.setOrganization(organization(organizationId, "Takım"));
		bottleneck.setTitle(title);
		bottleneck.setStatus(BottleneckStatus.OPEN);
		return bottleneck;
	}

	private static ActionItem actionItem(UUID organizationId) {
		ActionItem item = new ActionItem();
		item.setOrganization(organization(organizationId, "Takım"));
		return item;
	}

	private static MentorCount count(UUID mentorId, long total) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return mentorId;
			}

			@Override
			public Long getTotal() {
				return total;
			}
		};
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(role == UserRole.COORDINATOR ? "Elif Demir" : "Kullanıcı");
		user.setEmail(id + "@tekno.test");
		user.setRole(role);
		return user;
	}
}
