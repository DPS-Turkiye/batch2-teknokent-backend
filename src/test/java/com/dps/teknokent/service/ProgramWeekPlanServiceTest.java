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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramWeekRepository;

class ProgramWeekPlanServiceTest {

	private static final UUID COORDINATOR = UUID.randomUUID();
	private static final UUID ADMIN = UUID.randomUUID();
	private static final UUID PROGRAM_ID = UUID.randomUUID();

	private final CoordinatorProgramService coordinatorPrograms = mock(CoordinatorProgramService.class);
	private final ProgramWeekRepository weeks = mock(ProgramWeekRepository.class);
	private ProgramWeekPlanService service;
	private Program program;

	@BeforeEach
	void setUp() {
		service = new ProgramWeekPlanService(coordinatorPrograms, weeks);
		program = new Program();
		program.setId(PROGRAM_ID);
		// Four weeks: 5–11, 12–18, 19–25 and 26–28 October.
		program.setStartDate(LocalDate.of(2026, 10, 5));
		program.setEndDate(LocalDate.of(2026, 10, 28));
		when(coordinatorPrograms.requireSettingsEditor(COORDINATOR, PROGRAM_ID)).thenReturn(program);
		when(coordinatorPrograms.requireManagedProgram(ADMIN, PROGRAM_ID)).thenReturn(program);
		when(coordinatorPrograms.requireSettingsEditor(ADMIN, PROGRAM_ID))
				.thenThrow(new InvalidRequestException("An admin views program settings"));
		when(weeks.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@SuppressWarnings("unchecked")
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

	private static ReplaceProgramWeeksRequest.WeekInput input(int week, String title) {
		return new ReplaceProgramWeeksRequest.WeekInput(week, title, List.of(), List.of(), List.of(), false);
	}
}
