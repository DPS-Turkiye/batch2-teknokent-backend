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
import com.dps.teknokent.dto.responses.ProgramWeekResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramRepository;
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
	private ProgramWeekService service;

	private Program program;
	private ProgramPhase discovery;
	private ProgramPhase prototype;
	private ProgramPhase demo;

	@BeforeEach
	void setUp() {
		service = new ProgramWeekService(lookup, menteePrograms, programs, phases, programMentors, weeks);
		program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("İŞKUR Gençlik Programı");
		program.setStartDate(START);
		program.setEndDate(END);
		discovery = phase(0, "Fikir doğrulama", "2026-10-05", "2026-10-25", "Problem tanımı, 5 kullanıcı görüşmesi");
		prototype = phase(1, "Prototip", "2026-11-04", "2026-11-22", "Çalışan ilk sürüm");
		demo = phase(2, "Demo hazırlığı", "2026-11-23", "2026-12-27", "Demo günü sunumu");
		when(programs.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(phases.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of(discovery, prototype, demo,
				phase(3, "Tarihsiz", null, null, null)));
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
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(
				week(1, "Tanışma"), week(2, "Kullanıcı görüşmeleri")));

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

	private ProgramWeekResponse build(LocalDate today) {
		return ProgramWeekService.build(program, phases.findByProgramIdOrderByPositionAsc(PROGRAM_ID),
				weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID), today);
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
