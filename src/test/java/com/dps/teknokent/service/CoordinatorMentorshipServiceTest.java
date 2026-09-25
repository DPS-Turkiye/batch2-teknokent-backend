package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipGoal;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.service.CoordinatorProgramService.Snapshot;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class CoordinatorMentorshipServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private ActionItemRepository actionItemRepository;
	@Mock
	private MeetingNoteRepository meetingNoteRepository;
	@Mock
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Mock
	private MentorshipGoalRepository mentorshipGoalRepository;
	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private NotificationService notificationService;
	@InjectMocks
	private CoordinatorMentorshipService service;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID AYSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private static final UUID KEREM_ID = UUID.fromString("00000000-0000-0000-0000-000000000016");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID LUMEN_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID KAVUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID TERRA_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private final Instant now = Instant.now();
	private final LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
	private final AppUser coordinator = user(COORDINATOR_ID, "Elif Demir", UserRole.COORDINATOR);
	private final AppUser ayse = user(AYSE_ID, "Ayşe Demir", UserRole.MENTOR);
	private final AppUser kerem = user(KEREM_ID, "Kerem Sarı", UserRole.MENTOR);
	private final Program program = program();

	@Test
	void listsTheRiskiestMentorshipFirstWithItsTopicActionsAndMissingNotes() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Mentorship kavun = mentorship(ayse, KAVUN_ID, "Kavun", MentorshipStatus.ACTIVE);
		Mentorship terra = mentorship(kerem, TERRA_ID, "Terra", MentorshipStatus.PLANNED);
		Meeting lumenPast = meeting(ayse, lumen.getOrganization(), now.minus(18, ChronoUnit.DAYS), MeetingStatus.COMPLETED, null);
		Meeting kavunPast = meeting(ayse, kavun.getOrganization(), now.minus(3, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Notlar");
		Meeting kavunNext = meeting(ayse, kavun.getOrganization(), now.plus(2, ChronoUnit.DAYS), MeetingStatus.SCHEDULED, null);
		stubList(List.of(lumen, kavun, terra), List.of(lumenPast, kavunPast, kavunNext), Set.of(LUMEN_ID));
		when(actionItemRepository.findByOrganizationIdInAndStatusNotIn(any(), any()))
				.thenReturn(List.of(action(lumen.getOrganization(), today.minusDays(2), null)));
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(any(), eq(MentorshipRequestStatus.ACCEPTED)))
				.thenReturn(List.of(
						request(ayse, lumen.getOrganization(), "Eski konu", now.minus(90, ChronoUnit.DAYS)),
						request(ayse, lumen.getOrganization(), "Finansal model", now.minus(40, ChronoUnit.DAYS))));
		when(meetingNoteRepository.findByMeetingIdIn(any())).thenReturn(List.of());

		var rows = service.list(COORDINATOR_ID, null);

		assertThat(rows).extracting(row -> row.organizationName()).containsExactly("Lumen", "Terra", "Kavun");
		var risky = rows.getFirst();
		assertThat(risky.health()).isEqualTo(CoordinatorMentorshipHealth.RISKY);
		assertThat(risky.topic()).isEqualTo("Finansal model");
		assertThat(risky.daysSinceLastMeeting()).isEqualTo(18);
		assertThat(risky.nextMeetingAt()).isNull();
		assertThat(risky.openActions()).isEqualTo(1);
		assertThat(risky.overdueActions()).isEqualTo(1);
		assertThat(risky.missingNotes()).isEqualTo(1);
		assertThat(risky.programName()).isEqualTo("METU Stars 2026");
		assertThat(rows.get(1).health()).isEqualTo(CoordinatorMentorshipHealth.NEW);
		var healthy = rows.get(2);
		assertThat(healthy.health()).isEqualTo(CoordinatorMentorshipHealth.HEALTHY);
		assertThat(healthy.nextMeetingAt()).isEqualTo(kavunNext.getScheduledAt());
		assertThat(healthy.missingNotes()).isZero();
	}

	@Test
	void anActionFromAnotherMentorsMeetingIsNotCountedAgainstThisMentorship() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Meeting keremMeeting = meeting(kerem, lumen.getOrganization(), now.minus(1, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Not");
		stubList(List.of(lumen), List.of(keremMeeting), Set.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotIn(any(), any()))
				.thenReturn(List.of(action(lumen.getOrganization(), today.plusDays(3), keremMeeting)));
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(any(), any())).thenReturn(List.of());
		when(meetingNoteRepository.findByMeetingIdIn(any())).thenReturn(List.of());

		var row = service.list(COORDINATOR_ID, null).getFirst();

		assertThat(row.openActions()).isZero();
		assertThat(row.lastMeetingAt()).isNull();
	}

	@Test
	void aMentorshipOutsideTheCoordinatorsProgramsCannotBeOpened() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(lumen.getId())).thenReturn(Optional.of(lumen));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programStartupRepository.findByProgramIdIn(any())).thenReturn(List.of(startup(KAVUN_ID)));

		assertThatThrownBy(() -> service.detail(COORDINATOR_ID, lumen.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.remind(COORDINATOR_ID, lumen.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(notificationService, never()).create(any(), any(NotificationCategory.class), anyString(), anyString(), anyString());
	}

	@Test
	void theDrawerCountsNotedMeetingsAndGoalsButNotGoalTitles() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Meeting noted = meeting(ayse, lumen.getOrganization(), now.minus(20, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Not");
		Meeting silent = meeting(ayse, lumen.getOrganization(), now.minus(10, ChronoUnit.DAYS), MeetingStatus.COMPLETED, null);
		Meeting cancelled = meeting(ayse, lumen.getOrganization(), now.minus(5, ChronoUnit.DAYS), MeetingStatus.CANCELLED, null);
		stubScoped(lumen);
		stubSnapshot(List.of(lumen), List.of(cancelled, silent, noted), Set.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotIn(any(), any())).thenReturn(List.of());
		when(mentorshipRequestRepository.findByOrganizationIdInAndStatus(any(), any())).thenReturn(List.of());
		when(meetingNoteRepository.findByMeetingIdIn(any())).thenReturn(List.of());
		when(mentorshipGoalRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(lumen.getId()))
				.thenReturn(List.of(goal(true), goal(true), goal(false)));
		when(mentorshipRepository.countOrganizationsByMentor(any(), any())).thenReturn(List.of());
		when(mentorProfileRepository.findById(AYSE_ID)).thenReturn(Optional.empty());
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(LUMEN_ID))
				.thenReturn(List.of(user(UUID.randomUUID(), "Deniz Ak", UserRole.MENTEE)));

		var detail = service.detail(COORDINATOR_ID, lumen.getId());

		assertThat(detail.pastMeetings()).isEqualTo(2);
		assertThat(detail.notedMeetings()).isEqualTo(1);
		assertThat(detail.lastNoteAt()).isEqualTo(noted.getScheduledAt());
		assertThat(detail.goalsDone()).isEqualTo(2);
		assertThat(detail.goalsTotal()).isEqualTo(3);
		assertThat(detail.memberCount()).isEqualTo(1);
		assertThat(detail.founderName()).isEqualTo("Deniz Ak");
		assertThat(detail.meetings()).hasSize(3);
		assertThat(detail.mentorship().missingNotes()).isEqualTo(1);
	}

	@Test
	void aReminderReachesTheMentorAndEveryTeamMember() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		stubScoped(lumen);
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(LUMEN_ID)).thenReturn(List.of(
				user(UUID.randomUUID(), "Deniz Ak", UserRole.MENTEE),
				user(UUID.randomUUID(), "Mert Aydın", UserRole.MENTEE)));

		service.remind(COORDINATOR_ID, lumen.getId());

		verify(notificationService).create(
				eq(ayse), eq(NotificationCategory.MEETING_REMINDER), eq("Koordinatör hatırlatması"), anyString(),
				eq("/mentor-dashboard/calendar"));
		verify(notificationService, times(2))
				.create(any(), eq(NotificationCategory.MEETING_REMINDER), eq("Koordinatör hatırlatması"), anyString(),
						eq("/mentorships?tab=active"));
	}

	private void stubList(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations) {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program), null)).thenReturn(List.of(program));
		stubSnapshot(mentorships, meetings, overdueOrganizations);
	}

	private void stubScoped(Mentorship mentorship) {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(mentorship.getId())).thenReturn(Optional.of(mentorship));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programStartupRepository.findByProgramIdIn(any()))
				.thenReturn(List.of(startup(mentorship.getOrganization().getId())));
	}

	/** Mirrors what CoordinatorProgramService.snapshot derives from the same rows. */
	private void stubSnapshot(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations) {
		Map<UUID, List<Mentorship>> byOrganization = mentorships.stream()
				.collect(Collectors.groupingBy(mentorship -> mentorship.getOrganization().getId()));
		Map<String, Instant> lastMeeting = new HashMap<>();
		meetings.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED && meeting.getScheduledAt().isBefore(now))
				.sorted((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()))
				.forEach(meeting -> lastMeeting.putIfAbsent(
						CoordinatorProgramService.pairKey(meeting.getMentor().getId(), meeting.getOrganization().getId()),
						meeting.getScheduledAt()));
		Snapshot snapshot = new Snapshot(
				today,
				now,
				Map.of(),
				Map.of(),
				Map.of(PROGRAM_ID, byOrganization.keySet().stream().map(CoordinatorMentorshipServiceTest::startup).toList()),
				Map.of(),
				Map.of(),
				byOrganization,
				lastMeeting,
				meetings.stream().collect(Collectors.groupingBy(meeting -> meeting.getOrganization().getId())),
				Map.of(),
				overdueOrganizations);
		when(programService.snapshot(List.of(program))).thenReturn(snapshot);
	}

	private Mentorship mentorship(AppUser mentor, UUID organizationId, String name, MentorshipStatus status) {
		Organization organization = new Organization();
		organization.setId(organizationId);
		organization.setName(name);
		organization.setFounder(user(UUID.randomUUID(), "Deniz Ak", UserRole.MENTEE));
		Mentorship mentorship = new Mentorship();
		mentorship.setId(UUID.randomUUID());
		mentorship.setMentor(mentor);
		mentorship.setOrganization(organization);
		mentorship.setStatus(status);
		mentorship.setStartDate(today.minusDays(60));
		return mentorship;
	}

	private static Meeting meeting(AppUser mentor, Organization organization, Instant at, MeetingStatus status, String notes) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setOrganization(organization);
		meeting.setScheduledAt(at);
		meeting.setStatus(status);
		meeting.setNotes(notes);
		return meeting;
	}

	private static ActionItem action(Organization organization, LocalDate dueDate, Meeting meeting) {
		ActionItem item = new ActionItem();
		item.setId(UUID.randomUUID());
		item.setOrganization(organization);
		item.setStatus(ActionItemStatus.OPEN);
		item.setDueDate(dueDate);
		item.setMeeting(meeting);
		return item;
	}

	private static MentorshipRequest request(AppUser mentor, Organization organization, String topic, Instant createdAt) {
		MentorshipRequest request = new MentorshipRequest();
		request.setMentor(mentor);
		request.setOrganization(organization);
		request.setSupportArea(topic);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		request.setCreatedAt(createdAt);
		return request;
	}

	private static MentorshipGoal goal(boolean done) {
		MentorshipGoal goal = new MentorshipGoal();
		goal.setDone(done);
		return goal;
	}

	private static ProgramStartup startup(UUID organizationId) {
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(PROGRAM_ID);
		startup.setOrganizationId(organizationId);
		startup.setCreatedAt(Instant.parse("2026-02-01T09:00:00Z"));
		return startup;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(COORDINATOR_ID);
		program.setMeetingFrequencyDays(14);
		program.setStaleAfterDays(14);
		return program;
	}

	private static AppUser user(UUID id, String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setFullName(name);
		user.setRole(role);
		user.setEmail(id + "@tekno.test");
		return user;
	}
}
