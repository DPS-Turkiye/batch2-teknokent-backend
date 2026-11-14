package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingNote;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipGoal;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.repository.MentorProfileRepository;
import com.dps.teknokent.repository.MentorshipGoalRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.service.CoordinatorProgramService.Snapshot;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;

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
	private static final UUID OTHER_PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
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
		Meeting lumenPast = meeting(ayse, lumen.getOrganization(), now.minus(18, ChronoUnit.DAYS),
				MeetingStatus.COMPLETED, null);
		Meeting kavunPast = meeting(ayse, kavun.getOrganization(), now.minus(3, ChronoUnit.DAYS),
				MeetingStatus.COMPLETED, "Notlar");
		Meeting kavunNext = meeting(ayse, kavun.getOrganization(), now.plus(2, ChronoUnit.DAYS),
				MeetingStatus.SCHEDULED, null);
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
		Meeting keremMeeting = meeting(kerem, lumen.getOrganization(), now.minus(1, ChronoUnit.DAYS),
				MeetingStatus.COMPLETED, "Not");
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
	void aTeamInTwoProgramsHasEachMentorshipListedUnderTheProgramItWasMadeIn() {
		Program kulucka = program();
		kulucka.setId(OTHER_PROGRAM_ID);
		kulucka.setName("Kuluçka 2026");
		Mentorship metuMatch = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		metuMatch.setProgram(program);
		Mentorship kulucakMatch = mentorship(kerem, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		kulucakMatch.setProgram(kulucka);
		ProgramStartup inKulucka = startup(LUMEN_ID);
		inKulucka.setProgramId(OTHER_PROGRAM_ID);
		Snapshot snapshot = new Snapshot(today, now, Map.of(), Map.of(),
				Map.of(PROGRAM_ID, List.of(startup(LUMEN_ID)), OTHER_PROGRAM_ID, List.of(inKulucka)),
				Map.of(), Map.of(), Map.of(LUMEN_ID, List.of(metuMatch, kulucakMatch)),
				Map.of(), Map.of(), Map.of(), Set.of());
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program, kulucka));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program, kulucka), null))
				.thenReturn(List.of(program, kulucka));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program, kulucka), OTHER_PROGRAM_ID))
				.thenReturn(List.of(kulucka));
		when(programService.snapshot(any())).thenReturn(snapshot);

		assertThat(service.list(COORDINATOR_ID, null))
				.extracting(row -> row.mentorName() + " / " + row.programName())
				.containsExactlyInAnyOrder("Ayşe Demir / METU Stars 2026", "Kerem Sarı / Kuluçka 2026");
		assertThat(service.list(COORDINATOR_ID, OTHER_PROGRAM_ID))
				.extracting(row -> row.mentorName())
				.containsExactly("Kerem Sarı");
	}

	@Test
	void aMentorshipMadeInAnotherProgramCannotBeOpenedThroughTheTeam() {
		Program kulucka = program();
		kulucka.setId(OTHER_PROGRAM_ID);
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		lumen.setProgram(kulucka);
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(lumen.getId())).thenReturn(Optional.of(lumen));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));

		assertThatThrownBy(() -> service.detail(COORDINATOR_ID, lumen.getId()))
				.isInstanceOf(InvalidRequestException.class);
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
		verify(notificationService, never()).create(any(), any(NotificationCategory.class), anyString(), anyString(),
				anyString());
	}

	@Test
	void theDrawerCountsNotedMeetingsAndGoalsButNotGoalTitles() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Meeting noted = meeting(ayse, lumen.getOrganization(), now.minus(20, ChronoUnit.DAYS), MeetingStatus.COMPLETED,
				"Not");
		Meeting silent = meeting(ayse, lumen.getOrganization(), now.minus(10, ChronoUnit.DAYS), MeetingStatus.COMPLETED,
				null);
		Meeting cancelled = meeting(ayse, lumen.getOrganization(), now.minus(5, ChronoUnit.DAYS),
				MeetingStatus.CANCELLED, null);
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

	// --- Health: when a mentorship needs follow-up, and the order inside a bucket ---

	@Test
	void aMentorshipNeedsFollowUpOnceTheMeetingIntervalPassesOrItsTeamHasAnOpenIssue() {
		program.setMeetingFrequencyDays(7);
		program.setStaleAfterDays(21);
		Mentorship quietForTenDays = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Mentorship blocked = mentorship(ayse, KAVUN_ID, "Kavun", MentorshipStatus.ACTIVE);
		Mentorship neverMet = mentorship(kerem, TERRA_ID, "Terra", MentorshipStatus.ACTIVE);
		neverMet.setStartDate(today.minusDays(8));
		stubList(List.of(quietForTenDays, blocked, neverMet), List.of(
				meeting(ayse, quietForTenDays.getOrganization(), now.minus(10, ChronoUnit.DAYS),
						MeetingStatus.COMPLETED, "Not"),
				meeting(ayse, blocked.getOrganization(), now.minus(3, ChronoUnit.DAYS), MeetingStatus.COMPLETED,
						"Not")),
				Set.of(), Map.of(KAVUN_ID, List.of(new Bottleneck())));

		var rows = service.list(COORDINATOR_ID, null);

		assertThat(rows).extracting(row -> row.health()).containsOnly(CoordinatorMentorshipHealth.FOLLOW_UP);
		// The longest silence first; a pair that has never met has no silence to count and comes last.
		assertThat(rows).extracting(row -> row.organizationName()).containsExactly("Lumen", "Kavun", "Terra");
		assertThat(rows).extracting(row -> row.daysSinceLastMeeting()).containsExactly(10L, 3L, null);
	}

	@Test
	void aTeamWithAnOverdueActionNeedsFollowUpEvenWhenItMetRecently() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		stubList(List.of(lumen), List.of(meeting(ayse, lumen.getOrganization(), now.minus(2, ChronoUnit.DAYS),
				MeetingStatus.COMPLETED, "Not")), Set.of(LUMEN_ID));

		assertThat(service.list(COORDINATOR_ID, null)).singleElement()
				.satisfies(row -> assertThat(row.health()).isEqualTo(CoordinatorMentorshipHealth.FOLLOW_UP));
	}

	// --- A row's next meeting, open actions and missing notes ---

	@Test
	void theNextMeetingIsTheEarliestScheduledOneStillAhead() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Organization team = lumen.getOrganization();
		Meeting later = meeting(ayse, team, now.plus(5, ChronoUnit.DAYS), MeetingStatus.SCHEDULED, null);
		Meeting sooner = meeting(ayse, team, now.plus(2, ChronoUnit.DAYS), MeetingStatus.SCHEDULED, null);
		Meeting cancelled = meeting(ayse, team, now.plus(1, ChronoUnit.DAYS), MeetingStatus.CANCELLED, null);
		Meeting neverClosed = meeting(ayse, team, now.minus(1, ChronoUnit.DAYS), MeetingStatus.SCHEDULED, null);
		Meeting withoutATime = meeting(ayse, team, null, MeetingStatus.SCHEDULED, null);
		stubList(List.of(lumen), List.of(later, sooner, cancelled, neverClosed, withoutATime), Set.of());

		var row = service.list(COORDINATOR_ID, null).getFirst();

		assertThat(row.nextMeetingAt()).isEqualTo(sooner.getScheduledAt());
		// Only the meeting whose time has passed is missing its notes; one without a time has not happened.
		assertThat(row.missingNotes()).isEqualTo(1);
		assertThat(row.lastMeetingAt()).isEqualTo(neverClosed.getScheduledAt());
	}

	@Test
	void openActionsAreTheTeamsOwnAndThisMentorsAndOnlyADueDateBeforeTodayIsOverdue() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Organization team = lumen.getOrganization();
		Meeting withAyse = meeting(ayse, team, now.minus(1, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Not");
		Meeting withNoMentor = meeting(null, team, now.minus(2, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Not");
		Meeting withKerem = meeting(kerem, team, now.minus(3, ChronoUnit.DAYS), MeetingStatus.COMPLETED, "Not");
		stubList(List.of(lumen), List.of(withAyse), Set.of());
		when(actionItemRepository.findByOrganizationIdInAndStatusNotIn(Set.of(LUMEN_ID),
				List.of(ActionItemStatus.COMPLETED, ActionItemStatus.CANCELLED))).thenReturn(List.of(
						action(team, null, null),
						action(team, today.minusDays(1), withAyse),
						action(team, today, withNoMentor),
						action(team, today.minusDays(5), withKerem)));

		var row = service.list(COORDINATOR_ID, null).getFirst();

		assertThat(row.openActions()).isEqualTo(3);
		assertThat(row.overdueActions()).isEqualTo(1);
	}

	@Test
	void aMentorshipFromBeforeProgramsWereRecordedIsListedOnceForATeamInTwoPrograms() {
		Program kulucka = program();
		kulucka.setId(OTHER_PROGRAM_ID);
		kulucka.setName("Kuluçka 2026");
		Mentorship programless = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Mentorship finished = mentorship(kerem, LUMEN_ID, "Lumen", MentorshipStatus.COMPLETED);
		ProgramStartup inKulucka = startup(LUMEN_ID);
		inKulucka.setProgramId(OTHER_PROGRAM_ID);
		Snapshot snapshot = new Snapshot(today, now, Map.of(), Map.of(),
				Map.of(PROGRAM_ID, List.of(startup(LUMEN_ID)), OTHER_PROGRAM_ID, List.of(inKulucka)),
				Map.of(), Map.of(), Map.of(LUMEN_ID, List.of(programless, finished)),
				Map.of(), Map.of(), Map.of(), Set.of());
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program, kulucka));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program, kulucka), null))
				.thenReturn(List.of(program, kulucka));
		when(programService.snapshot(List.of(program, kulucka))).thenReturn(snapshot);

		var rows = service.list(COORDINATOR_ID, null);

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.id()).isEqualTo(programless.getId());
			assertThat(row.programName()).isEqualTo("METU Stars 2026");
		});
	}

	@Test
	void aProgramWithoutTeamsListsNothingAndLooksUpNoActionsOrNotes() {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program), PROGRAM_ID)).thenReturn(List.of(program));
		when(programService.snapshot(List.of(program))).thenReturn(new Snapshot(today, now, Map.of(), Map.of(),
				Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

		assertThat(service.list(COORDINATOR_ID, PROGRAM_ID)).isEmpty();
		verifyNoInteractions(actionItemRepository, meetingNoteRepository, mentorshipRequestRepository);
	}

	// --- The drawer ---

	@Test
	void aMeetingCountsAsNotedThroughItsOutcomesOrALiveNoteThatHasText() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		Organization team = lumen.getOrganization();
		team.setFounder(null);
		Meeting withOutcomes = meeting(ayse, team, now.minus(30, ChronoUnit.DAYS), MeetingStatus.COMPLETED, " ");
		withOutcomes.setOutcomes("Fiyatlandırma kararı");
		withOutcomes.setAgenda("  Fiyatlandırma  ");
		withOutcomes.setDurationMinutes(45);
		Meeting withLiveNote = meeting(ayse, team, now.minus(20, ChronoUnit.DAYS), MeetingStatus.COMPLETED, null);
		Meeting withEmptyLiveNote = meeting(ayse, team, now.minus(10, ChronoUnit.DAYS), MeetingStatus.COMPLETED,
				null);
		stubScoped(lumen);
		stubSnapshot(List.of(lumen), List.of(withOutcomes, withLiveNote, withEmptyLiveNote), Set.of());
		MeetingNote written = MeetingNote.emptyFor(withLiveNote);
		written.setContent("Canlı notlar");
		when(meetingNoteRepository.findByMeetingIdIn(
				List.of(withOutcomes.getId(), withLiveNote.getId(), withEmptyLiveNote.getId())))
				.thenReturn(List.of(written, MeetingNote.emptyFor(withEmptyLiveNote)));
		when(mentorshipRepository.countOrganizationsByMentor(eq(List.of(AYSE_ID)), any()))
				.thenReturn(List.of(load(3)));
		MentorProfile profile = new MentorProfile();
		profile.setMenteeCapacity(5);
		when(mentorProfileRepository.findById(AYSE_ID)).thenReturn(Optional.of(profile));

		var detail = service.detail(COORDINATOR_ID, lumen.getId());

		assertThat(detail.pastMeetings()).isEqualTo(3);
		assertThat(detail.notedMeetings()).isEqualTo(2);
		assertThat(detail.lastNoteAt()).isEqualTo(withLiveNote.getScheduledAt());
		assertThat(detail.mentorship().missingNotes()).isEqualTo(1);
		assertThat(detail.meetings()).extracting(row -> row.hasNotes()).containsExactly(true, true, false);
		assertThat(detail.meetings().getFirst().title()).isEqualTo("Fiyatlandırma");
		assertThat(detail.meetings().getFirst().durationMinutes()).isEqualTo(45);
		assertThat(detail.meetings().get(1).title()).isEqualTo(Texts.forRequest("label.mentorshipMeeting"));
		assertThat(detail.mentorLoad()).isEqualTo(3);
		assertThat(detail.mentorCapacity()).isEqualTo(5);
		assertThat(detail.founderName()).isNull();
		assertThat(detail.memberCount()).isZero();
		assertThat(detail.meetingFrequencyDays()).isEqualTo(14);
		assertThat(detail.staleAfterDays()).isEqualTo(14);
	}

	@Test
	void aMentorshipThatDoesNotExistIsReportedAsNotFound() {
		UUID unknown = UUID.randomUUID();
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.detail(COORDINATOR_ID, unknown))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(unknown.toString());
		assertThatThrownBy(() -> service.remind(COORDINATOR_ID, unknown))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(notificationService);
	}

	@Test
	void someoneWhoManagesNoProgramCannotOpenAMentorship() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(lumen.getId())).thenReturn(Optional.of(lumen));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of());

		assertThatThrownBy(() -> service.detail(COORDINATOR_ID, lumen.getId()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not in one of your programs");
		verifyNoInteractions(programStartupRepository);
	}

	// --- Reminders ---

	@Test
	void aReminderNamesTheProgramTheMentorshipWasMadeInAndThePair() {
		Mentorship lumen = mentorship(ayse, LUMEN_ID, "Lumen", MentorshipStatus.ACTIVE);
		lumen.setProgram(program);
		AppUser deniz = user(UUID.randomUUID(), "Deniz Ak", UserRole.MENTEE);
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(lumen.getId())).thenReturn(Optional.of(lumen));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(LUMEN_ID)).thenReturn(List.of(deniz));

		service.remind(COORDINATOR_ID, lumen.getId());

		String body = Texts.get(ayse, "notification.coordinatorReminder.body", "METU Stars 2026",
				"Ayşe Demir ↔ Lumen");
		verify(notificationService).create(ayse, NotificationCategory.MEETING_REMINDER,
				Texts.get(ayse, "notification.coordinatorReminder.title"), body, "/mentor-dashboard/calendar");
		verify(notificationService).create(deniz, NotificationCategory.MEETING_REMINDER,
				Texts.get(deniz, "notification.coordinatorReminder.title"), body, "/mentorships?tab=active");
		// The mentorship names its program, so the team's programs are not consulted.
		verifyNoInteractions(programStartupRepository);
	}

	private static MentorCount load(long teams) {
		return new MentorCount() {
			@Override
			public UUID getMentorId() {
				return AYSE_ID;
			}

			@Override
			public Long getTotal() {
				return teams;
			}
		};
	}

	private void stubList(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations) {
		stubList(mentorships, meetings, overdueOrganizations, Map.of());
	}

	private void stubList(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations,
			Map<UUID, List<Bottleneck>> openBottlenecks) {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program), null)).thenReturn(List.of(program));
		stubSnapshot(mentorships, meetings, overdueOrganizations, openBottlenecks);
	}

	private void stubScoped(Mentorship mentorship) {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(mentorshipRepository.findById(mentorship.getId())).thenReturn(Optional.of(mentorship));
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programStartupRepository.findByProgramIdIn(any()))
				.thenReturn(List.of(startup(mentorship.getOrganization().getId())));
	}

	private void stubSnapshot(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations) {
		stubSnapshot(mentorships, meetings, overdueOrganizations, Map.of());
	}

	/** Mirrors what CoordinatorProgramService.snapshot derives from the same rows. */
	private void stubSnapshot(List<Mentorship> mentorships, List<Meeting> meetings, Set<UUID> overdueOrganizations,
			Map<UUID, List<Bottleneck>> openBottlenecks) {
		Map<UUID, List<Mentorship>> byOrganization = mentorships.stream()
				.collect(Collectors.groupingBy(mentorship -> mentorship.getOrganization().getId()));
		Map<String, Instant> lastMeeting = new HashMap<>();
		meetings.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED
						&& meeting.getScheduledAt() != null
						&& meeting.getScheduledAt().isBefore(now))
				.sorted((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()))
				.forEach(meeting -> lastMeeting.putIfAbsent(
						CoordinatorProgramService.pairKey(meeting.getMentor().getId(),
								meeting.getOrganization().getId()),
						meeting.getScheduledAt()));
		Snapshot snapshot = new Snapshot(
				today,
				now,
				Map.of(),
				Map.of(),
				Map.of(PROGRAM_ID,
						byOrganization.keySet().stream().map(CoordinatorMentorshipServiceTest::startup).toList()),
				Map.of(),
				Map.of(),
				byOrganization,
				lastMeeting,
				meetings.stream().collect(Collectors.groupingBy(meeting -> meeting.getOrganization().getId())),
				openBottlenecks,
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

	private static Meeting meeting(AppUser mentor, Organization organization, Instant at, MeetingStatus status,
			String notes) {
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

	private static MentorshipRequest request(AppUser mentor, Organization organization, String topic,
			Instant createdAt) {
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
