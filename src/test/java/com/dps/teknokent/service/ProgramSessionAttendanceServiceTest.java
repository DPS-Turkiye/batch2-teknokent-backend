package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.domain.WeekVisibility;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramAttendance;
import com.dps.teknokent.entity.ProgramSessionQr;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.repository.ProgramAttendanceRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramSessionQrRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/** The check-in window of a timed session, at fixed moments in Istanbul. */
@ExtendWith(MockitoExtension.class)
class ProgramSessionAttendanceServiceTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
	private static final String TOKEN = "t".repeat(43);

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MenteeProgramService menteeProgramService;
	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private ProgramAttendanceService attendanceService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramWeekRepository programWeeks;
	@Mock
	private ProgramAttendanceRepository attendanceRepository;
	@Mock
	private ProgramSessionQrRepository qrRepository;

	private final AppUser mentee = new AppUser();
	private final Program program = new Program();
	private final ProgramWeek week = new ProgramWeek();

	// Growth Circuit week 1: the Monday lesson runs 18:00–20:00.
	@BeforeEach
	void setUp() {
		mentee.setId(UUID.randomUUID());
		mentee.setRole(UserRole.MENTEE);
		program.setId(UUID.randomUUID());
		program.setName("Growth Circuit");
		program.setStatus(ProgramStatus.ACTIVE);
		program.setStartDate(MONDAY);
		program.setEndDate(MONDAY.plusDays(34));
		week.setId(UUID.randomUUID());
		week.setProgramId(program.getId());
		week.setWeekNumber(1);
		week.setTitle("Müşteri / Alıcı / PMF");
		week.setStartDate(MONDAY);
		week.setEndDate(MONDAY.plusDays(4));
		week.setSessions(new ArrayList<>(List.of(
				new ProgramWeekSession(MONDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), "Online", "PMF"))));
		ProgramSessionQr qr = new ProgramSessionQr();
		qr.setProgramId(program.getId());
		qr.setWeekNumber(1);
		qr.setSessionKind(AttendanceSessionKind.LESSON);
		qr.setToken(TOKEN);

		when(qrRepository.findByToken(TOKEN)).thenReturn(Optional.of(qr));
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(menteeProgramService.programOf(mentee)).thenReturn(Optional.of(program.getId()));
		when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
		// Lenient: a program that is not running is refused before its plan is read.
		lenient().when(programWeeks.findByProgramIdOrderByWeekNumberAsc(program.getId())).thenReturn(List.of(week));
	}

	@Test
	void refusesBeforeHalfAnHourAheadAndSaysWhenItOpens() {
		noAttendanceYet();

		assertThatThrownBy(() -> serviceAt(17, 29).checkIn(mentee.getId(), TOKEN))
				.isInstanceOfSatisfying(ConflictException.class, refused -> {
					assertThat(refused.code()).isEqualTo(ProgramSessionAttendanceService.CODE_QR_NOT_VALID);
					assertThat(refused.getMessage()).contains("09.11.2026", "17:30", "21:00");
				});
		verify(attendanceRepository, never()).saveAndFlush(any());
	}

	@Test
	void recordsFromHalfAnHourBeforeTheStart() {
		noAttendanceYet();

		var response = serviceAt(17, 30).checkIn(mentee.getId(), TOKEN);

		ArgumentCaptor<ProgramAttendance> saved = ArgumentCaptor.forClass(ProgramAttendance.class);
		verify(attendanceRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getMethod()).isEqualTo(AttendanceMethod.QR);
		assertThat(saved.getValue().getSessionKind()).isEqualTo(AttendanceSessionKind.LESSON);
		assertThat(response.alreadyRecorded()).isFalse();
		assertThat(response.startTime()).isEqualTo(LocalTime.of(18, 0));
		assertThat(response.title()).isEqualTo("PMF");
	}

	@Test
	void aWeekHiddenFromMenteesStillTakesItsQrAttendance() {
		week.setMenteeVisibility(WeekVisibility.HIDDEN);
		noAttendanceYet();

		var response = serviceAt(18, 0).checkIn(mentee.getId(), TOKEN);

		verify(attendanceRepository).saveAndFlush(any());
		assertThat(response.alreadyRecorded()).isFalse();
		assertThat(response.title()).isEqualTo("PMF");
	}

	@Test
	void refusesAnHourAfterTheEnd() {
		noAttendanceYet();

		assertThatThrownBy(() -> serviceAt(21, 0).checkIn(mentee.getId(), TOKEN))
				.isInstanceOf(ConflictException.class);
		verify(attendanceRepository, never()).saveAndFlush(any());
	}

	@Test
	void someoneAlreadyRecordedSeesItEvenAfterTheWindowCloses() {
		ProgramAttendance earlier = new ProgramAttendance();
		earlier.setCheckedInAt(MONDAY.atTime(18, 5).atZone(MeetingSlotTimes.APP_ZONE).toInstant());
		when(attendanceRepository.findByProgramIdAndUserIdAndWeekNumberAndSessionKind(program.getId(),
				mentee.getId(), 1, AttendanceSessionKind.LESSON)).thenReturn(Optional.of(earlier));

		var response = serviceAt(23, 0).checkIn(mentee.getId(), TOKEN);

		assertThat(response.alreadyRecorded()).isTrue();
		assertThat(response.checkedInAt()).isEqualTo(earlier.getCheckedInAt());
		verify(attendanceRepository, never()).saveAndFlush(any());
	}

	// Two scans at once: the one that loses the unique key answers "already recorded", which the page shows as done.
	@Test
	void aScanThatLosesARaceAnswersAlreadyRecorded() {
		noAttendanceYet();
		when(attendanceRepository.saveAndFlush(any()))
				.thenThrow(new DataIntegrityViolationException("uq_program_attendance"));

		assertThatThrownBy(() -> serviceAt(18, 30).checkIn(mentee.getId(), TOKEN))
				.isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.code())
						.isEqualTo(ProgramSessionAttendanceService.CODE_ALREADY_RECORDED));
	}

	@Test
	void aProgramThatIsNotRunningTakesNoCheckIn() {
		program.setStatus(ProgramStatus.COMPLETED);

		assertThatThrownBy(() -> serviceAt(18, 30).checkIn(mentee.getId(), TOKEN))
				.isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.code())
						.isEqualTo(ProgramSessionAttendanceService.CODE_QR_NOT_VALID));
		verify(attendanceRepository, never()).saveAndFlush(any());
	}

	@Test
	void aProgramThatTakesNoAttendanceAnswersLikeAnyDeadLink() {
		program.setAttendanceEnabled(false);

		assertThatThrownBy(() -> serviceAt(18, 30).checkIn(mentee.getId(), TOKEN))
				.isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.code())
						.isEqualTo(ProgramSessionAttendanceService.CODE_QR_NOT_VALID));
		verify(attendanceRepository, never()).saveAndFlush(any());
	}

	private void noAttendanceYet() {
		when(attendanceRepository.findByProgramIdAndUserIdAndWeekNumberAndSessionKind(program.getId(),
				mentee.getId(), 1, AttendanceSessionKind.LESSON)).thenReturn(Optional.empty());
	}

	private ProgramSessionAttendanceService serviceAt(int hour, int minute) {
		Clock clock = Clock.fixed(MONDAY.atTime(hour, minute).atZone(MeetingSlotTimes.APP_ZONE).toInstant(),
				MeetingSlotTimes.APP_ZONE);
		return new ProgramSessionAttendanceService(domainLookupService, menteeProgramService,
				coordinatorProgramService, attendanceService, programRepository, programWeeks, attendanceRepository,
				qrRepository, clock);
	}
}
