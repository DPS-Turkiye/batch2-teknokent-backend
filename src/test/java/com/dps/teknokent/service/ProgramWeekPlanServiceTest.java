package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.ProgramSchedule;

class ProgramWeekPlanServiceTest {

	private static final UUID COORDINATOR = UUID.randomUUID();
	private static final UUID ADMIN = UUID.randomUUID();
	private static final UUID PROGRAM_ID = UUID.randomUUID();

	private final CoordinatorProgramService coordinatorPrograms = mock(CoordinatorProgramService.class);
	private final ProgramWeekRepository weeks = mock(ProgramWeekRepository.class);
	private final ProgramSpecialDayRepository events = mock(ProgramSpecialDayRepository.class);
	private ProgramWeekPlanService service;
	private Program program;

	@BeforeEach
	void setUp() {
		service = new ProgramWeekPlanService(coordinatorPrograms, weeks, events);
		program = new Program();
		program.setId(PROGRAM_ID);
		// Four weeks: 5–11, 12–18, 19–25 and 26–28 October.
		program.setStartDate(LocalDate.of(2026, 10, 5));
		program.setEndDate(LocalDate.of(2026, 10, 28));
		when(coordinatorPrograms.requireSettingsEditor(COORDINATOR, PROGRAM_ID)).thenReturn(program);
		when(coordinatorPrograms.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		when(coordinatorPrograms.requireSettingsEditor(ADMIN, PROGRAM_ID))
				.thenThrow(new InvalidRequestException("An admin views program settings"));
		when(weeks.saveAll(anyList())).thenAnswer(invocation -> {
			List<ProgramWeek> saved = invocation.getArgument(0);
			when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(saved);
			return saved;
		});
	}

	@Test
	@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
	void savingReplacesThePlanInWeekOrderWithItsLinesAndDatesFromTheProgram() {
		ProgramWeekPlanResponse saved = service.replace(COORDINATOR, PROGRAM_ID, new ReplaceProgramWeeksRequest(null,
				List.of(input(4, " Sunum "), new ReplaceProgramWeeksRequest.WeekInput(1, "Tanışma",
						List.of(" Program ", "Ekip"), List.of(), null, true))));

		ArgumentCaptor<List<ProgramWeek>> rows = ArgumentCaptor.forClass(List.class);
		verify(weeks).deleteByProgramId(PROGRAM_ID);
		verify(weeks).saveAll(rows.capture());
		assertThat(rows.getValue()).extracting(ProgramWeek::getWeekNumber).containsExactly(1, 4);
		assertThat(rows.getValue().get(0).getTopics()).isEqualTo("Program\nEkip");
		assertThat(rows.getValue().get(0).getUpdatedBy()).isEqualTo(COORDINATOR);
		assertThat(saved.totalWeeks()).isEqualTo(4);
		assertThat(saved.weeks().get(1).title()).isEqualTo("Sunum");
		assertThat(saved.weeks().get(1).startDate()).isEqualTo(LocalDate.of(2026, 10, 26));
		assertThat(saved.weeks().get(1).endDate()).isEqualTo(LocalDate.of(2026, 10, 28));
		assertThat(saved.weeks().get(0).inferred()).isTrue();
	}

	@Test
	void aWeekTwiceOrPastTheProgramsLastWeekIsRefusedAndNothingChanges() {
		assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM_ID,
				new ReplaceProgramWeeksRequest(null, List.of(input(2, "A"), input(2, "B")))))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM_ID,
				new ReplaceProgramWeeksRequest(null, List.of(input(5, "Program bittikten sonra")))))
				.isInstanceOf(InvalidRequestException.class);
		verify(weeks, never()).deleteByProgramId(any());
	}

	@Test
	void anAdminReadsThePlanButOnlyTheProgramsCoordinatorsChangeIt() {
		ProgramWeek saved = new ProgramWeek();
		saved.setWeekNumber(2);
		saved.setTitle("Görüşmeler");
		saved.setDeliverables("Notlar\n\nÖzet");
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(saved));

		ProgramWeekPlanResponse plan = service.plan(ADMIN, PROGRAM_ID);

		assertThat(plan.weeks()).singleElement().satisfies(week -> {
			assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 10, 12));
			assertThat(week.deliverables()).containsExactly("Notlar", "Özet");
		});
		assertThatThrownBy(() -> service.replace(ADMIN, PROGRAM_ID,
				new ReplaceProgramWeeksRequest(null, List.of(input(1, "Tanışma")))))
				.isInstanceOf(InvalidRequestException.class);
		verify(weeks, never()).deleteByProgramId(any());
	}

	@Test
	void printedDatesSurviveSavingEvenWhenTheProgramCalendarDiffers() {
		LocalDate printed = LocalDate.of(2026, 11, 9);
		var session = new ReplaceProgramWeeksRequest.SessionInput(printed, null, null, "Salon", "Oturum");
		var input = new ReplaceProgramWeeksRequest.WeekInput(5, "PDF week", List.of(), List.of(), List.of(),
				false, printed, printed.plusDays(4), List.of(session));
		var saved = service.replace(COORDINATOR, PROGRAM_ID, new ReplaceProgramWeeksRequest(null, List.of(input)));
		assertThat(saved.weeks()).singleElement().satisfies(week -> {
			assertThat(week.week()).isEqualTo(5);
			assertThat(week.startDate()).isEqualTo(printed);
			assertThat(week.endDate()).isEqualTo(printed.plusDays(4));
			assertThat(week.datesFromSource()).isTrue();
			assertThat(week.sessions()).singleElement().satisfies(row -> {
				assertThat(row.date()).isEqualTo(printed);
				assertThat(row.place()).isEqualTo("Salon");
			});
		});
	}

	@Test
	void incompleteOrReversedPrintedDatesAreRejectedBeforeDeletingAnything() {
		LocalDate date = LocalDate.of(2026, 11, 9);
		for (LocalDate end : new LocalDate[] { null, date.minusDays(1) }) {
			var input = new ReplaceProgramWeeksRequest.WeekInput(1, "PDF week", List.of(), List.of(), List.of(),
					false, date, end, List.of());
			assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM_ID,
					new ReplaceProgramWeeksRequest(null, List.of(input))))
					.isInstanceOf(InvalidRequestException.class);
		}
		verify(weeks, never()).deleteByProgramId(any());
	}

	@Test
	void aSessionEndingBeforeItStartsOrAWeekLongerThanAMonthIsRefused() {
		LocalDate monday = LocalDate.of(2026, 11, 9);
		var backwards = new ReplaceProgramWeeksRequest.SessionInput(monday, LocalTime.of(18, 0), LocalTime.of(17, 0),
				null, "Eğitim");
		var withBackwardsSession = new ReplaceProgramWeeksRequest.WeekInput(1, "PDF", List.of(), List.of(), List.of(),
				false, monday, monday.plusDays(6), List.of(backwards));
		var tooLong = new ReplaceProgramWeeksRequest.WeekInput(1, "PDF", List.of(), List.of(), List.of(), false,
				monday, monday.plusDays(31), List.of());

		for (var week : List.of(withBackwardsSession, tooLong)) {
			assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM_ID,
					new ReplaceProgramWeeksRequest(null, List.of(week), List.of())))
					.isInstanceOf(InvalidRequestException.class);
		}
		verify(weeks, never()).deleteByProgramId(any());
	}

	@Test
	void leavingTheSpecialDaysOutKeepsTheSavedOnes() {
		service.replace(COORDINATOR, PROGRAM_ID, new ReplaceProgramWeeksRequest(null, List.of(), null));

		verify(events, never()).deleteByProgramId(any());
		verify(weeks).deleteByProgramId(PROGRAM_ID);
	}

	@Test
	void datesThatWouldMakeTheProgramLongerThanTwoYearsAreNotApplied() {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(1);
		week.setTitle("PDF");
		week.setStartDate(LocalDate.of(2026, 11, 9));
		week.setEndDate(LocalDate.of(2026, 11, 13));
		ProgramSpecialDay misread = new ProgramSpecialDay();
		misread.setDate(LocalDate.of(2028, 12, 15));
		misread.setTitle("Demo Günü");
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(week));
		when(events.findByProgramIdOrderByDateAsc(PROGRAM_ID)).thenReturn(List.of(misread));

		assertThatThrownBy(() -> service.alignDates(COORDINATOR, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(program.getEndDate()).isEqualTo(LocalDate.of(2026, 10, 28));
	}

	@Test
	void datesThatWouldHoldFewerWeeksThanThePlanAreNotApplied() {
		ProgramWeek first = new ProgramWeek();
		first.setWeekNumber(1);
		first.setTitle("PDF");
		first.setStartDate(LocalDate.of(2026, 11, 9));
		first.setEndDate(LocalDate.of(2026, 11, 13));
		ProgramWeek third = new ProgramWeek();
		third.setWeekNumber(3);
		third.setTitle("Undated");
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(first, third));

		assertThatThrownBy(() -> service.alignDates(COORDINATOR, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(program.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 5));
	}

	@Test
	void onlyTheAssignedCoordinatorCanAlignDatesAndAdminsRemainReadOnly() {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(1);
		week.setTitle("PDF");
		week.setStartDate(LocalDate.of(2026, 11, 9));
		week.setEndDate(LocalDate.of(2026, 11, 13));
		when(weeks.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(week));
		assertThat(service.plan(ADMIN, PROGRAM_ID).dateCheck()).isNotNull();
		assertThatThrownBy(() -> service.alignDates(ADMIN, PROGRAM_ID)).isInstanceOf(InvalidRequestException.class);
		UUID unassigned = UUID.randomUUID();
		when(coordinatorPrograms.requireSettingsEditor(unassigned, PROGRAM_ID))
				.thenThrow(new InvalidRequestException("Not assigned"));
		assertThatThrownBy(() -> service.alignDates(unassigned, PROGRAM_ID))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(program.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 5));
		assertThat(service.alignDates(COORDINATOR, PROGRAM_ID).dateCheck()).isNull();
		assertThat(program.getStartDate()).isEqualTo(week.getStartDate());
		assertThat(program.getEndDate()).isEqualTo(week.getEndDate());
	}

	@Test
	void savesSpecialDaysOutsideWeeksAndIncludesThemInTheDateCheck() {
		when(events.saveAll(anyList())).thenAnswer(invocation -> {
			List<ProgramSpecialDay> saved = invocation.getArgument(0);
			when(events.findByProgramIdOrderByDateAsc(PROGRAM_ID)).thenReturn(saved);
			return saved;
		});
		var day = new ReplaceProgramWeeksRequest.SpecialDayInput(LocalDate.of(2026, 11, 8),
				java.time.LocalTime.of(13, 0), null, "Cozone", " Bootcamp ", List.of("Opening"));
		var response = service.replace(COORDINATOR, PROGRAM_ID,
				new ReplaceProgramWeeksRequest(null, List.of(), List.of(day)));
		assertThat(response.weeks()).isEmpty();
		assertThat(response.specialDays()).singleElement().satisfies(saved -> {
			assertThat(saved.title()).isEqualTo("Bootcamp");
			assertThat(saved.date()).isEqualTo(day.date());
			assertThat(saved.endTime()).isNull();
			assertThat(saved.details()).containsExactly("Opening");
		});
		assertThat(response.dateCheck().syllabusStart()).isEqualTo(day.date());
		verify(events).deleteByProgramId(PROGRAM_ID);
	}

	@Test
	void keepingPastSpecialDaysPreservesSavedContentAndOnlyAcceptsFutureReplacements() {
		LocalDate today = LocalDate.of(2026, 11, 9);
		ProgramSpecialDay past = new ProgramSpecialDay();
		past.setDate(today.minusDays(1));
		past.setTitle("Saved Bootcamp");
		past.setDetails("Opening");
		when(events.findByProgramIdOrderByDateAsc(PROGRAM_ID)).thenReturn(List.of(past));
		var replacement = new ReplaceProgramWeeksRequest.SpecialDayInput(today.minusDays(1), null, null, null,
				"Changed", List.of());
		var future = new ReplaceProgramWeeksRequest.SpecialDayInput(today.plusDays(1), null, null, null, "Future",
				List.of());
		assertThat(service.keepPastSpecialDays(PROGRAM_ID, List.of(replacement, future), List.of(), today))
				.extracting(ReplaceProgramWeeksRequest.SpecialDayInput::title)
				.containsExactly("Saved Bootcamp", "Future");
	}

	@Test
	void withNothingSavedThePastSpecialDaysOfTheFileAreKept() {
		LocalDate today = LocalDate.of(2026, 11, 18);
		when(events.findByProgramIdOrderByDateAsc(PROGRAM_ID)).thenReturn(List.of());
		var past = new ReplaceProgramWeeksRequest.SpecialDayInput(today.minusDays(20), null, null, null, "Açılış",
				List.of());
		var future = new ReplaceProgramWeeksRequest.SpecialDayInput(today.plusDays(1), null, null, null, "Demo Day",
				List.of());
		assertThat(service.keepPastSpecialDays(PROGRAM_ID, List.of(past, future), List.of(), today))
				.containsExactly(past, future);
	}

	@Test
	void aPastSpecialDayOfTheFileFillsOnlyDaysTheKeptWeeksDoNotCover() {
		LocalDate today = LocalDate.of(2026, 11, 18);
		ProgramSpecialDay saved = new ProgramSpecialDay();
		saved.setDate(LocalDate.of(2026, 11, 4));
		saved.setTitle("Saved visit");
		when(events.findByProgramIdOrderByDateAsc(PROGRAM_ID)).thenReturn(List.of(saved));
		var movedInsideAKeptWeek = new ReplaceProgramWeeksRequest.SpecialDayInput(LocalDate.of(2026, 11, 5), null,
				null, null, "Moved visit", List.of());
		var beforeTheKeptWeeks = new ReplaceProgramWeeksRequest.SpecialDayInput(LocalDate.of(2026, 10, 28), null,
				null, null, "Opening", List.of());
		List<ProgramSchedule.Dates> keptWeeks = List.of(
				new ProgramSchedule.Dates(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9)));

		assertThat(service.keepPastSpecialDays(PROGRAM_ID, List.of(beforeTheKeptWeeks, movedInsideAKeptWeek),
				keptWeeks, today)).extracting(ReplaceProgramWeeksRequest.SpecialDayInput::title)
				.containsExactly("Saved visit", "Opening");
	}

	private static ReplaceProgramWeeksRequest.WeekInput input(int week, String title) {
		return new ReplaceProgramWeeksRequest.WeekInput(week, title, List.of(), List.of(), List.of(), false);
	}
}
