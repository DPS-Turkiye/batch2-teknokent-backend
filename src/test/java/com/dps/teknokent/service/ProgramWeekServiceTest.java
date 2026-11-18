package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.ProgramWeekResponse;
import com.dps.teknokent.dto.responses.ProgramWeeksSoFarResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;

class ProgramWeekServiceTest {

	// Twelve weeks from Monday 5 October to Sunday 27 December.
	private static final LocalDate START = LocalDate.of(2026, 10, 5);
	private static final LocalDate END = LocalDate.of(2026, 12, 27);
	private static final UUID PROGRAM_ID = UUID.randomUUID();

	private final DomainLookupService lookup = mock(DomainLookupService.class);
	private final MenteeProgramService menteePrograms = mock(MenteeProgramService.class);
	private final ProgramRepository programs = mock(ProgramRepository.class);
	private final ProgramPhaseRepository phases = mock(ProgramPhaseRepository.class);
	private final ProgramMentorRepository programMentors = mock(ProgramMentorRepository.class);
	private final ProgramWeekRepository weeks = mock(ProgramWeekRepository.class);
	private final ProgramSpecialDayRepository specialDays = mock(ProgramSpecialDayRepository.class);
	private ProgramWeekService service;

	private Program program;
	private ProgramPhase discovery;
	private ProgramPhase prototype;
	private ProgramPhase demo;
	private List<ProgramPhase> phaseRows;
	private List<ProgramWeek> weekRows = List.of();

	@BeforeEach
	void setUp() {
		service = new ProgramWeekService(lookup, menteePrograms, programs, phases, programMentors, weeks, specialDays);
		program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("İŞKUR Gençlik Programı");
		program.setStartDate(START);
		program.setEndDate(END);
		discovery = phase(0, "Fikir doğrulama", "2026-10-05", "2026-10-25", "Problem tanımı, 5 kullanıcı görüşmesi");
		prototype = phase(1, "Prototip", "2026-11-04", "2026-11-22", "Çalışan ilk sürüm");
		demo = phase(2, "Demo hazırlığı", "2026-11-23", "2026-12-27", "Demo günü sunumu");
		when(programs.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		phaseRows = List.of(discovery, prototype, demo, phase(3, "Tarihsiz", null, null, null));
		when(phases.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(phaseRows);
	}

	@Test
	void januaryFourthPitchSessionRemainsVisibleInItsPdfWeekTen() {
		ProgramWeek saved = week(10, "Pitch");
		saved.setStartDate(LocalDate.of(2026, 12, 28));
		saved.setEndDate(LocalDate.of(2027, 1, 3));
		LocalDate sessionDay = LocalDate.of(2027, 1, 4);
		saved.getSessions().add(new ProgramWeekSession(sessionDay, null, null, null, "Pitch"));
		program.setEndDate(LocalDate.of(2027, 1, 9));
		var response = ProgramWeekService.build(program, List.of(), List.of(saved), sessionDay);
		assertThat(response.week()).isEqualTo(10);
		assertThat(response.plan().sessions()).singleElement()
				.satisfies(session -> assertThat(session.date()).isEqualTo(sessionDay));
	}

	@Test
	void storedDatesSelectTheSameWeekNumberAsAttendanceBeforeProgramStart() {
		ProgramWeek saved = week(3, "PDF week");
		saved.setStartDate(START.minusDays(7));
		saved.setEndDate(START.minusDays(1));
		ProgramWeekResponse response = ProgramWeekService.build(program, List.of(), List.of(saved), START.minusDays(3));
		assertThat(response.week()).isEqualTo(3);
		assertThat(response.notStarted()).isFalse();
		assertThat(response.weekStart()).isEqualTo(saved.getStartDate());
		assertThat(response.plan().title()).isEqualTo("PDF week");
	}

	@Test
	void midPhaseGivesTheWeekNumberItsDaysAndThePhaseRunningToday() {
		ProgramWeekResponse week = build(LocalDate.of(2026, 10, 14));

		assertThat(week.week()).isEqualTo(2);
		assertThat(week.totalWeeks()).isEqualTo(12);
		assertThat(week.weekStart()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(week.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 18));
		assertThat(week.phases()).extracting(ProgramWeekResponse.Phase::name).containsExactly("Fikir doğrulama");
		assertThat(week.phases().get(0).current()).isTrue();
		assertThat(week.phases().get(0).expectedOutput()).isEqualTo("Problem tanımı, 5 kullanıcı görüşmesi");
		assertThat(week.hasSyllabus()).isTrue();
	}

	@Test
	void theWeeksPlannedContentComesWithItsDates() {
		weekRows = List.of(week(1, "Tanışma"), week(2, "Kullanıcı görüşmeleri"));
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(weekRows);

		ProgramWeekResponse week = build(LocalDate.of(2026, 10, 14));

		assertThat(week.plan().title()).isEqualTo("Kullanıcı görüşmeleri");
		assertThat(week.plan().topics()).containsExactly("Problem tanımı", "Hedef kitle");
		assertThat(week.plan().deliverables()).containsExactly("5 görüşme notu");
		assertThat(week.plan().startDate()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(week.plan().endDate()).isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	void aWeekWithoutPlannedContentHasNoPlanAndAWeeklyPlanAloneCountsAsASyllabus() {
		ProgramWeekResponse week = ProgramWeekService.build(program, List.of(), List.of(week(1, "Tanışma")),
				LocalDate.of(2026, 10, 14));

		assertThat(week.plan()).isNull();
		assertThat(week.hasSyllabus()).isTrue();
	}

	@Test
	void aPhaseStartingLaterThisWeekIsAlreadyShownButNotAsCurrent() {
		ProgramWeekResponse week = build(LocalDate.of(2026, 11, 2));

		assertThat(week.week()).isEqualTo(5);
		assertThat(week.phases()).extracting(ProgramWeekResponse.Phase::name).containsExactly("Prototip");
		assertThat(week.phases().get(0).current()).isFalse();
	}

	@Test
	void aWeekBetweenTwoPhasesHasNoPhaseButStillHasASyllabus() {
		ProgramWeekResponse week = build(LocalDate.of(2026, 10, 27));

		assertThat(week.week()).isEqualTo(4);
		assertThat(week.phases()).isEmpty();
		assertThat(week.hasSyllabus()).isTrue();
	}

	@Test
	void theLastDayIsStillTheLastWeekWithItsPhase() {
		ProgramWeekResponse week = build(END);

		assertThat(week.week()).isEqualTo(12);
		assertThat(week.weekEnd()).isEqualTo(END);
		assertThat(week.ended()).isFalse();
		assertThat(week.phases()).extracting(ProgramWeekResponse.Phase::name).containsExactly("Demo hazırlığı");
		assertThat(week.phases().get(0).current()).isTrue();
	}

	@Test
	void afterTheEndThereIsNoWeekAndNoPhase() {
		ProgramWeekResponse week = build(END.plusDays(1));

		assertThat(week.ended()).isTrue();
		assertThat(week.week()).isNull();
		assertThat(week.phases()).isEmpty();
	}

	@Test
	void beforeTheStartThereIsNoWeekYet() {
		ProgramWeekResponse week = build(START.minusDays(1));

		assertThat(week.notStarted()).isTrue();
		assertThat(week.week()).isNull();
		assertThat(week.weekStart()).isNull();
		assertThat(week.phases()).isEmpty();
	}

	@Test
	void withoutASyllabusTheWeekIsKnownButEmpty() {
		ProgramWeekResponse week = ProgramWeekService.build(program, List.of(), List.of(), LocalDate.of(2026, 10, 14));

		assertThat(week.week()).isEqualTo(2);
		assertThat(week.hasSyllabus()).isFalse();
		assertThat(week.phases()).isEmpty();
	}

	@Test
	void withoutAStartDateItShowsThePhaseRunningToday() {
		program.setStartDate(null);

		ProgramWeekResponse week = build(LocalDate.of(2026, 11, 10));

		assertThat(week.week()).isNull();
		assertThat(week.phases()).extracting(ProgramWeekResponse.Phase::name).containsExactly("Prototip");
	}

	@Test
	void aMenteeGetsTheirOwnProgramsWeek() {
		AppUser mentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(mentee)).thenReturn(Optional.of(PROGRAM_ID));

		assertThat(service.weekOf(mentee.getId(), null, LocalDate.of(2026, 10, 14)).programId()).isEqualTo(PROGRAM_ID);
		assertThat(service.weekOf(mentee.getId(), PROGRAM_ID, LocalDate.of(2026, 10, 14)).week()).isEqualTo(2);
	}

	@Test
	void aMenteeCannotReadAnotherProgram() {
		AppUser mentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(mentee)).thenReturn(Optional.of(UUID.randomUUID()));

		assertThatThrownBy(() -> service.weekOf(mentee.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void anAcceptedMentorOfTheProgramMayReadItAPendingOneMayNot() {
		AppUser mentor = user(UserAccountStatus.APPROVED, UserRole.MENTOR);
		ProgramMentor membership = new ProgramMentor();
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentors.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.of(membership));

		assertThat(service.weekOf(mentor.getId(), PROGRAM_ID, START).week()).isEqualTo(1);

		membership.setStatus(ProgramMentorStatus.PENDING);
		assertThatThrownBy(() -> service.weekOf(mentor.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void anAccountNotYetApprovedIsRefused() {
		AppUser pending = user(UserAccountStatus.PENDING, UserRole.MENTEE);
		when(menteePrograms.programOf(pending)).thenReturn(Optional.of(PROGRAM_ID));

		assertThatThrownBy(() -> service.weekOf(pending.getId(), null, START))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void someoneInNoProgramGetsNotFound() {
		AppUser mentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(mentee)).thenReturn(Optional.empty());
		AppUser mentor = user(UserAccountStatus.APPROVED, UserRole.MENTOR);

		assertThatThrownBy(() -> service.weekOf(mentee.getId(), null, START))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.weekOf(mentor.getId(), null, START))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void pastWeeksStopAtTheRunningWeekAndTheNextOneAppearsOnItsFirstDay() {
		List<ProgramWeek> plan = List.of(week(1, "Tanışma"), week(2, "Görüşmeler"), week(3, "Prototip"));

		ProgramWeeksSoFarResponse dayBefore = ProgramWeekService.reached(program, plan, List.of(),
				LocalDate.of(2026, 10, 11));
		assertThat(dayBefore.currentWeek()).isEqualTo(1);
		assertThat(dayBefore.weeks()).extracting(week -> week.plan().week()).containsExactly(1);

		ProgramWeeksSoFarResponse firstDay = ProgramWeekService.reached(program, plan, List.of(),
				LocalDate.of(2026, 10, 12));
		assertThat(firstDay.currentWeek()).isEqualTo(2);
		assertThat(firstDay.weeks()).extracting(week -> week.plan().week()).containsExactly(1, 2);
		assertThat(firstDay.weeks()).extracting(ProgramWeeksSoFarResponse.Week::current).containsExactly(false, true);
		assertThat(firstDay.weeks().get(0).plan().title()).isEqualTo("Tanışma");
		assertThat(firstDay.weeks().get(0).plan().topics()).containsExactly("Problem tanımı", "Hedef kitle");
		assertThat(firstDay.weeks().get(0).plan().activities()).containsExactly("Görüşme planı yap");
		assertThat(firstDay.weeks().get(0).plan().deliverables()).containsExactly("5 görüşme notu");
		assertThat(firstDay.weeks().get(0).plan().startDate()).isEqualTo(START);
		assertThat(firstDay.weeks().get(0).plan().endDate()).isEqualTo(LocalDate.of(2026, 10, 11));
	}

	@Test
	void aWeeksWorkshopIsSentOnlyFromTheDayItsWeekStarts() {
		ProgramWeek second = printedWeek(2, "2026-11-03", "2026-11-09");
		ProgramWeek third = printedWeek(3, "2026-11-10", "2026-11-16");
		third.setActivities("Workshop: Müşteri keşfi");
		third.getSessions().add(new ProgramWeekSession(LocalDate.of(2026, 11, 12), null, null, null,
				"Workshop: Müşteri keşfi"));
		List<ProgramWeek> plan = List.of(second, third);

		ProgramWeeksSoFarResponse lastDayOfWeekTwo = ProgramWeekService.reached(program, plan, List.of(),
				LocalDate.of(2026, 11, 9));
		assertThat(lastDayOfWeekTwo.weeks()).extracting(week -> week.plan().week()).containsExactly(2);
		assertThat(lastDayOfWeekTwo.weeks()).flatExtracting(week -> week.plan().sessions()).isEmpty();
		assertThat(lastDayOfWeekTwo.weeks()).flatExtracting(week -> week.plan().activities())
				.doesNotContain("Workshop: Müşteri keşfi");

		ProgramWeeksSoFarResponse weekThreeStarts = ProgramWeekService.reached(program, plan, List.of(),
				LocalDate.of(2026, 11, 10));
		assertThat(weekThreeStarts.currentWeek()).isEqualTo(3);
		assertThat(weekThreeStarts.weeks().getLast().plan().sessions())
				.extracting(ProgramWeekPlanResponse.Session::date).containsExactly(LocalDate.of(2026, 11, 12));
		assertThat(weekThreeStarts.weeks().getLast().plan().activities()).containsExactly("Workshop: Müşteri keşfi");
	}

	@Test
	void nothingDatedAfterTheRunningWeekLeavesEvenFromAnEarlierWeek() {
		ProgramWeek first = printedWeek(1, "2026-10-05", "2026-10-11");
		first.getSessions().add(new ProgramWeekSession(LocalDate.of(2026, 10, 6), null, null, null, "Açılış"));
		first.getSessions()
				.add(new ProgramWeekSession(LocalDate.of(2026, 11, 20), null, null, null, "Sonraki ziyaret"));
		ProgramWeek second = printedWeek(2, "2026-10-12", "2026-10-18");
		List<ProgramSpecialDay> days = List.of(specialDay("2026-10-08", "Bootcamp"), specialDay("2026-10-17", "Prova"),
				specialDay("2026-10-20", "Demo Günü"));

		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program, List.of(first, second), days,
				LocalDate.of(2026, 10, 13));

		assertThat(response.weeks().get(0).plan().sessions()).extracting(ProgramWeekPlanResponse.Session::title)
				.containsExactly("Açılış");
		assertThat(response.weeks().get(0).specialDays()).extracting(ProgramWeekPlanResponse.SpecialDay::title)
				.containsExactly("Bootcamp");
		assertThat(response.weeks().get(1).specialDays()).extracting(ProgramWeekPlanResponse.SpecialDay::title)
				.containsExactly("Prova");
	}

	@Test
	void aSessionDatedTodayDoesNotOpenAWeekThatHasNotBegun() {
		ProgramWeek second = printedWeek(2, "2026-10-26", "2026-11-01");
		ProgramWeek third = printedWeek(3, "2026-11-10", "2026-11-16");
		third.getSessions().add(new ProgramWeekSession(LocalDate.of(2026, 11, 5), null, null, null, "Ön hazırlık"));
		third.getSessions().add(new ProgramWeekSession(LocalDate.of(2026, 11, 12), null, null, null, "Workshop"));

		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program, List.of(second, third), List.of(),
				LocalDate.of(2026, 11, 5));

		assertThat(response.currentWeek()).isNull();
		assertThat(response.weeks()).extracting(week -> week.plan().week()).containsExactly(2);
	}

	@Test
	void aWeekWithoutItsOwnDatesInAPrintedPlanWaitsForALaterPrintedWeek() {
		List<ProgramWeek> plan = List.of(printedWeek(1, "2026-10-12", "2026-10-18"),
				printedWeek(2, "2026-10-19", "2026-10-25"), week(3, "Tarihsiz hafta"),
				printedWeek(4, "2026-11-02", "2026-11-08"));

		// The program calendar would start week 3 on 19 October; the syllabus has not reached it yet.
		assertThat(ProgramWeekService.reached(program, plan, List.of(), LocalDate.of(2026, 10, 27)).weeks())
				.extracting(week -> week.plan().week()).containsExactly(1, 2);
		assertThat(ProgramWeekService.reached(program, plan, List.of(), LocalDate.of(2026, 11, 2)).weeks())
				.extracting(week -> week.plan().week()).containsExactly(1, 2, 3, 4);
		assertThat(ProgramWeekService.reached(program, List.of(printedWeek(1, "2026-10-12", "2026-10-18"),
				week(2, "Tarihsiz hafta")), List.of(), END.plusDays(1)).weeks())
				.extracting(week -> week.plan().week()).containsExactly(1, 2);
	}

	@Test
	void onADayBetweenTwoPrintedWeeksOnlyTheWeeksAlreadyBegunAreSent() {
		List<ProgramWeek> plan = List.of(printedWeek(1, "2026-10-05", "2026-10-11"),
				printedWeek(2, "2026-10-19", "2026-10-25"));

		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program, plan, List.of(),
				LocalDate.of(2026, 10, 14));

		assertThat(response.currentWeek()).isNull();
		assertThat(response.weeks()).extracting(week -> week.plan().week()).containsExactly(1);
		assertThat(response.weeks().get(0).current()).isFalse();
	}

	@Test
	void beforeTheProgramStartsNoWeekIsSent() {
		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program,
				List.of(week(1, "Tanışma"), week(2, "Görüşmeler")), List.of(), START.minusDays(1));

		assertThat(response.currentWeek()).isNull();
		assertThat(response.weeks()).isEmpty();
	}

	@Test
	void afterTheProgramEndsEveryWeekIsSentWithItsSessions() {
		ProgramWeek last = week(12, "Demo");
		last.getSessions().add(new ProgramWeekSession(END, null, null, null, "Demo Günü"));

		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program,
				List.of(week(1, "Tanışma"), week(6, "Ara"), last), List.of(), END.plusDays(1));

		assertThat(response.currentWeek()).isNull();
		assertThat(response.weeks()).extracting(week -> week.plan().week()).containsExactly(1, 6, 12);
		assertThat(response.weeks().getLast().plan().sessions()).hasSize(1);
	}

	@Test
	void withoutAnyDatesNoWeekCanBePlacedSoNoneIsSent() {
		program.setStartDate(null);

		ProgramWeeksSoFarResponse response = ProgramWeekService.reached(program, List.of(week(1, "Tanışma")),
				List.of(), LocalDate.of(2026, 10, 14));

		assertThat(response.weeks()).isEmpty();
	}

	@Test
	void aMenteeReadsTheirOwnProgramsWeeksSoFar() {
		AppUser mentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(mentee)).thenReturn(Optional.of(PROGRAM_ID));
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID))
				.thenReturn(List.of(week(1, "Tanışma"), week(2, "Görüşmeler"), week(3, "Prototip")));
		when(specialDays.findByProgramIdOrderByDateAsc(PROGRAM_ID))
				.thenReturn(List.of(specialDay("2026-10-08", "Bootcamp")));

		ProgramWeeksSoFarResponse response = service.weeksSoFar(mentee.getId(), null, LocalDate.of(2026, 10, 14));

		assertThat(response.programId()).isEqualTo(PROGRAM_ID);
		assertThat(response.programName()).isEqualTo("İŞKUR Gençlik Programı");
		assertThat(response.weeks()).extracting(week -> week.plan().week()).containsExactly(1, 2);
		assertThat(response.weeks().get(0).specialDays()).hasSize(1);
	}

	@Test
	void theWeeksSoFarAreRefusedToAnyoneOutsideTheProgram() {
		AppUser otherMentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(otherMentee)).thenReturn(Optional.of(UUID.randomUUID()));
		AppUser strangerMentor = user(UserAccountStatus.APPROVED, UserRole.MENTOR);
		when(programMentors.findByProgramIdAndMentorId(PROGRAM_ID, strangerMentor.getId()))
				.thenReturn(Optional.empty());
		AppUser pending = user(UserAccountStatus.PENDING, UserRole.MENTEE);
		when(menteePrograms.programOf(pending)).thenReturn(Optional.of(PROGRAM_ID));
		AppUser coordinator = user(UserAccountStatus.APPROVED, UserRole.COORDINATOR);

		assertThatThrownBy(() -> service.weeksSoFar(otherMentee.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.weeksSoFar(strangerMentor.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.weeksSoFar(pending.getId(), null, START))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.weeksSoFar(coordinator.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void anAcceptedMentorReadsTheProgramsWeeksSoFarAPendingOneMayNot() {
		AppUser mentor = user(UserAccountStatus.APPROVED, UserRole.MENTOR);
		ProgramMentor membership = new ProgramMentor();
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		when(programMentors.findByProgramIdAndMentorId(PROGRAM_ID, mentor.getId())).thenReturn(Optional.of(membership));
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(week(1, "Tanışma")));

		assertThat(service.weeksSoFar(mentor.getId(), PROGRAM_ID, START).weeks()).hasSize(1);

		membership.setStatus(ProgramMentorStatus.PENDING);
		assertThatThrownBy(() -> service.weeksSoFar(mentor.getId(), PROGRAM_ID, START))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void aMenteeInNoProgramHasNoWeeksToRead() {
		AppUser mentee = user(UserAccountStatus.APPROVED, UserRole.MENTEE);
		when(menteePrograms.programOf(mentee)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.weeksSoFar(mentee.getId(), null, START))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	private ProgramWeekResponse build(LocalDate today) {
		return ProgramWeekService.build(program, phaseRows, weekRows, today);
	}

	private AppUser user(UserAccountStatus status, UserRole... roles) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Ayşe Yılmaz");
		user.setStatus(status);
		user.setPrimaryRole(roles[0]);
		for (UserRole role : roles) {
			user.grant(role, PermissionSource.ADMIN, Instant.now());
		}
		when(lookup.requireUser(user.getId())).thenReturn(user);
		return user;
	}

	private static ProgramWeek week(int number, String title) {
		ProgramWeek week = new ProgramWeek();
		week.setProgramId(PROGRAM_ID);
		week.setWeekNumber(number);
		week.setTitle(title);
		week.setTopics("Problem tanımı\nHedef kitle");
		week.setActivities("Görüşme planı yap");
		week.setDeliverables("5 görüşme notu");
		return week;
	}

	private static ProgramWeek printedWeek(int number, String start, String end) {
		ProgramWeek week = week(number, "Hafta " + number);
		week.setStartDate(LocalDate.parse(start));
		week.setEndDate(LocalDate.parse(end));
		return week;
	}

	private static ProgramSpecialDay specialDay(String date, String title) {
		ProgramSpecialDay day = new ProgramSpecialDay();
		day.setId(UUID.randomUUID());
		day.setProgramId(PROGRAM_ID);
		day.setDate(LocalDate.parse(date));
		day.setTitle(title);
		day.setDetails("");
		return day;
	}

	private static ProgramPhase phase(int position, String name, String start, String end, String output) {
		ProgramPhase phase = new ProgramPhase();
		phase.setId(UUID.randomUUID());
		phase.setProgramId(PROGRAM_ID);
		phase.setPosition(position);
		phase.setName(name);
		phase.setStartDate(start == null ? null : LocalDate.parse(start));
		phase.setEndDate(end == null ? null : LocalDate.parse(end));
		phase.setExpectedOutput(output);
		return phase;
	}
}
