package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.ProgramCalendarEntryKind;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MemberProgramEventResponse;
import com.dps.teknokent.dto.responses.ProgramCalendarPlanItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;

@ExtendWith(MockitoExtension.class)
class ProgramCalendarServiceTest {

	private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PROGRAM_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final Instant FROM = Instant.parse("2026-09-21T00:00:00Z");
	private static final Instant TO = Instant.parse("2026-09-28T00:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

	@Mock
	private DomainLookupService domainLookupService;

	@Mock
	private ProgramStartupRepository programStartupRepository;

	@Mock
	private ProgramMentorRepository programMentorRepository;

	@Mock
	private ProgramEventRepository programEventRepository;

	@Mock
	private ProgramRepository programRepository;

	@Mock
	private ProgramPlanCalendarService planCalendar;

	@InjectMocks
	private ProgramCalendarService service;

	@Test
	void aMenteeSeesTheEventsOfTheirStartupsPrograms() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTEE, organization()));
		ProgramStartup membership = new ProgramStartup();
		membership.setProgramId(PROGRAM_ID);
		membership.setOrganizationId(ORG_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM,
				TO))
				.thenReturn(List.of(event()));

		var events = service.eventsFor(ACTOR_ID, FROM, TO);

		assertThat(events).singleElement().satisfies(item -> {
			assertThat(item.programName()).isEqualTo("METU Stars 2026");
			assertThat(item.title()).isEqualTo("Ara rapor teslimi");
			assertThat(item.kind()).isEqualTo(ProgramCalendarEntryKind.DEADLINE);
			assertThat(item.plan()).isNull();
		});
	}

	@Test
	void aMentorSeesOnlyProgramsTheyWereAcceptedInto() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTOR, null));
		ProgramMentor membership = new ProgramMentor();
		membership.setProgramId(PROGRAM_ID);
		when(programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(ACTOR_ID,
				ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM,
				TO))
				.thenReturn(List.of(event()));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).hasSize(1);
	}

	// A peer mentor (İŞKUR) is a mentee in one program and mentors in another: the calendar shows both.
	@Test
	void anAccountOnBothSidesSeesTheProgramsOfBoth() {
		UUID mentoredIn = UUID.randomUUID();
		AppUser student = user(UserRole.MENTEE, organization());
		student.grant(UserRole.MENTOR, PermissionSource.BADGE, Instant.now());
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(student);
		ProgramMentor mentoring = new ProgramMentor();
		mentoring.setProgramId(mentoredIn);
		when(programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(ACTOR_ID,
				ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(mentoring));
		ProgramStartup team = new ProgramStartup();
		team.setProgramId(PROGRAM_ID);
		team.setOrganizationId(ORG_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(team));
		when(programRepository.findAllById(List.of(mentoredIn, PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(
				List.of(mentoredIn, PROGRAM_ID), FROM, TO)).thenReturn(List.of(event()));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).hasSize(1);
	}

	@Test
	void aMenteeWithoutAStartupSeesNothing() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTEE, null));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).isEmpty();
		verifyNoInteractions(programEventRepository, planCalendar);
	}

	@Test
	void aCoordinatorUsesTheirOwnCalendarInstead() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.COORDINATOR, null));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).isEmpty();
		verifyNoInteractions(programEventRepository, planCalendar);
	}

	@Test
	void theWeeklyPlansEntriesComeInDateOrderAmongTheEventsWithTheProgramsName() {
		stubMentee();
		ProgramEvent deadline = event();
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM,
				TO)).thenReturn(List.of(deadline));
		ProgramCalendarPlanItem plan = new ProgramCalendarPlanItem(4, "Sorunu Doğrula", null, "Sarper", List.of(),
				true);
		when(planCalendar.forParticipants(any(), eq(TODAY), eq(FROM), eq(TO), eq(List.of(deadline))))
				.thenReturn(List.of(
						new ProgramPlanCalendarService.Entry(UUID.randomUUID(), PROGRAM_ID,
								ProgramCalendarEntryKind.SESSION,
								"Değer Önerisi", Instant.parse("2026-09-21T21:00:00Z"), null, plan)));

		var entries = service.eventsFor(ACTOR_ID, FROM, TO, TODAY);

		assertThat(entries).extracting(MemberProgramEventResponse::kind)
				.containsExactly(ProgramCalendarEntryKind.SESSION, ProgramCalendarEntryKind.DEADLINE);
		assertThat(entries.getFirst().programName()).isEqualTo("METU Stars 2026");
		assertThat(entries.getFirst().plan()).isEqualTo(plan);
	}

	@Test
	void anAcceptedMentorGetsThePlanOfTheProgramsTheyMentorIn() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTOR, null));
		ProgramMentor membership = new ProgramMentor();
		membership.setProgramId(PROGRAM_ID);
		when(programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(ACTOR_ID,
				ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(planCalendar.forParticipants(any(), eq(TODAY), eq(FROM), eq(TO), eq(List.of())))
				.thenReturn(List.of(new ProgramPlanCalendarService.Entry(UUID.randomUUID(), PROGRAM_ID,
						ProgramCalendarEntryKind.SPECIAL_DAY, "Demo Day", Instant.parse("2026-09-25T10:00:00Z"), null,
						new ProgramCalendarPlanItem(null, null, "KKM", null, List.of(), false))));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO, TODAY)).extracting(MemberProgramEventResponse::kind)
				.containsExactly(ProgramCalendarEntryKind.SPECIAL_DAY);
	}

	// The plan's weeks are for approved participants only (as on Süreç); the program's own events are unchanged.
	@Test
	void anAccountNotYetApprovedGetsNoneOfTheWeeklyPlan() {
		AppUser pending = user(UserRole.MENTEE, organization());
		pending.setStatus(UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(pending);
		ProgramStartup membership = new ProgramStartup();
		membership.setProgramId(PROGRAM_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM,
				TO)).thenReturn(List.of(event()));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO, TODAY)).hasSize(1);
		verifyNoInteractions(planCalendar);
	}

	// End to end through the real plan calendar: nothing of a week that has not begun reaches a participant.
	@Test
	void aParticipantNeverGetsASessionOfAWeekThatHasNotBegun() {
		ProgramWeekRepository weeks = mock(ProgramWeekRepository.class);
		ProgramSpecialDayRepository days = mock(ProgramSpecialDayRepository.class);
		ProgramCalendarService real = new ProgramCalendarService(domainLookupService, programStartupRepository,
				programMentorRepository, programEventRepository, programRepository,
				new ProgramPlanCalendarService(weeks, days));
		stubMentee();
		Program program = program();
		program.setStartDate(LocalDate.of(2026, 9, 7));
		program.setEndDate(LocalDate.of(2026, 11, 15));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program));
		ProgramWeek running = week(3, LocalDate.of(2026, 9, 21), "Açılış dersi", "Workshop: Persona");
		ProgramWeek next = week(4, LocalDate.of(2026, 9, 28), "Pazar analizi");
		when(weeks.findByProgramIdIn(any())).thenReturn(List.of(running, next));
		Instant to = Instant.parse("2026-10-05T00:00:00Z");

		ProgramSpecialDay demo = new ProgramSpecialDay();
		demo.setId(UUID.randomUUID());
		demo.setProgramId(PROGRAM_ID);
		demo.setDate(LocalDate.of(2026, 10, 2));
		demo.setTitle("Demo Day");
		when(days.findByProgramIdInAndDateBetweenOrderByDateAsc(any(), any(), any())).thenReturn(List.of(demo));

		var onTheDayBefore = real.eventsFor(ACTOR_ID, FROM, to, LocalDate.of(2026, 9, 20));
		var onTheFirstDay = real.eventsFor(ACTOR_ID, FROM, to, LocalDate.of(2026, 9, 21));

		// The special day of week 4 is there ahead of time; no session of a week not yet begun is.
		assertThat(onTheDayBefore).extracting(MemberProgramEventResponse::title).containsExactly("Demo Day");
		assertThat(onTheFirstDay).extracting(MemberProgramEventResponse::title)
				.containsExactly("Açılış dersi", "Workshop: Persona", "Demo Day");
	}

	@Test
	void rejectsAnInvertedOrTooLongRange() {
		assertThatThrownBy(() -> service.eventsFor(ACTOR_ID, TO, FROM))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.eventsFor(ACTOR_ID, FROM, FROM.plusSeconds(200L * 86_400)))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(domainLookupService);
	}

	private void stubMentee() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTEE, organization()));
		ProgramStartup membership = new ProgramStartup();
		membership.setProgramId(PROGRAM_ID);
		membership.setOrganizationId(ORG_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(membership));
		lenient().when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
	}

	/** A printed week whose lesson is on its first day and whose workshop two days later. */
	private static ProgramWeek week(int number, LocalDate start, String lesson, String... later) {
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setProgramId(PROGRAM_ID);
		week.setWeekNumber(number);
		week.setTitle(lesson);
		week.setStartDate(start);
		week.setEndDate(start.plusDays(6));
		week.getSessions().add(new ProgramWeekSession(start, null, null, null, lesson));
		for (String title : later) {
			week.getSessions().add(new ProgramWeekSession(start.plusDays(2), null, null, null, title));
		}
		return week;
	}

	private static AppUser user(UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setId(ACTOR_ID);
		user.setRole(role);
		user.setOrganization(organization);
		return user;
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(ORG_ID);
		return organization;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		return program;
	}

	private static ProgramEvent event() {
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(PROGRAM_ID);
		event.setTitle("Ara rapor teslimi");
		event.setKind(ProgramEventKind.DEADLINE);
		event.setStartsAt(Instant.parse("2026-09-25T20:59:00Z"));
		return event;
	}
}
