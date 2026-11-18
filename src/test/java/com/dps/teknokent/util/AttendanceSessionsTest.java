package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;

class AttendanceSessionsTest {

	private static final LocalDate PROGRAM_START = LocalDate.of(2026, 10, 26);
	private static final LocalDate PROGRAM_END = LocalDate.of(2027, 1, 10);
	// Build week 4: printed Monday 16.11, its undated workshop placed on Wednesday 18.11.
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

	@Test
	void theLessonIsOnTheWeeksPrintedFirstDayAndLastsTheDayWithoutATime() {
		ProgramWeek week = week(4, session(MONDAY.plusDays(2), null, null, "Workshop: Burcu Tarhan"));

		List<AttendanceSessions.Session> sessions = AttendanceSessions.of(schedule(week), week, 4);

		assertThat(sessions).extracting(AttendanceSessions.Session::kind)
				.containsExactly(AttendanceSessionKind.LESSON, AttendanceSessionKind.WORKSHOP);
		AttendanceSessions.Session lesson = sessions.getFirst();
		assertThat(lesson.date()).isEqualTo(MONDAY);
		assertThat(lesson.startTime()).isNull();
		assertThat(lesson.title()).isEqualTo("Hafta 4");
		assertThat(sessions.get(1).date()).isEqualTo(MONDAY.plusDays(2));
		assertThat(sessions.get(1).title()).isEqualTo("Workshop: Burcu Tarhan");
	}

	// Growth Circuit: Monday lesson online, Wednesday session, Friday atölye at Cozone.
	@Test
	void aWeekOfThreeSessionsTakesTheLessonAndTheLastButNotTheOneInBetween() {
		ProgramWeek week = week(1,
				session(MONDAY.plusDays(4), LocalTime.of(16, 30), LocalTime.of(18, 30), "PMF, Founder&Growth"),
				session(MONDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), "Müşteri / Alıcı / PMF"),
				session(MONDAY.plusDays(2), LocalTime.of(17, 0), LocalTime.of(19, 0), "Uygulayıcı / gerçek vaka"));

		List<AttendanceSessions.Session> sessions = AttendanceSessions.of(schedule(week), week, 1);

		assertThat(sessions).hasSize(2);
		assertThat(sessions.getFirst().startTime()).isEqualTo(LocalTime.of(18, 0));
		assertThat(sessions.getFirst().title()).isEqualTo("Müşteri / Alıcı / PMF");
		assertThat(sessions.get(1).date()).isEqualTo(MONDAY.plusDays(4));
	}

	@Test
	void aSessionTitledAsTheWorkshopWinsOverALaterOne() {
		ProgramWeek week = week(8,
				session(MONDAY.plusDays(2), null, null, "ODTÜ 70 ATÖLYESİ"),
				session(MONDAY.plusDays(4), null, null, "Ziyaret: CoZone"));

		assertThat(AttendanceSessions.find(schedule(week), week, 8, AttendanceSessionKind.WORKSHOP))
				.extracting(AttendanceSessions.Session::date)
				.isEqualTo(MONDAY.plusDays(2));
	}

	@Test
	void aWeekWithNothingAfterItsFirstDayHasNoWorkshop() {
		ProgramWeek week = week(10, session(MONDAY, null, null, "  "));

		assertThat(AttendanceSessions.of(schedule(week), week, 10)).singleElement()
				.satisfies(lesson -> assertThat(lesson.title()).isEqualTo("Hafta 10"));
		assertThat(AttendanceSessions.find(schedule(week), week, 10, AttendanceSessionKind.WORKSHOP)).isNull();
		assertThat(AttendanceSessions.find(schedule(week), week, 10, AttendanceSessionKind.WEEK)).isNull();
	}

	@Test
	void aWeekTheProgramCalendarDatesHasItsLessonOnTheCalendarsFirstDay() {
		ProgramSchedule computed = ProgramSchedule.computed(PROGRAM_START, PROGRAM_END);

		assertThat(AttendanceSessions.of(computed, null, 3)).singleElement()
				.satisfies(lesson -> {
					assertThat(lesson.date()).isEqualTo(PROGRAM_START.plusDays(14));
					assertThat(lesson.title()).isNull();
				});
	}

	@Test
	void aProgramWithoutDatesHasNoSessions() {
		assertThat(AttendanceSessions.of(ProgramSchedule.computed(null, null), null, 1)).isEmpty();
	}

	@Test
	void aTimedSessionOpensHalfAnHourBeforeAndClosesAnHourAfter() {
		AttendanceSessions.Session session = new AttendanceSessions.Session(1, AttendanceSessionKind.LESSON, MONDAY,
				LocalTime.of(18, 0), LocalTime.of(20, 0), null);

		assertThat(session.opensAt()).isEqualTo(istanbul(MONDAY, 17, 30));
		assertThat(session.closesAt()).isEqualTo(istanbul(MONDAY, 21, 0));
		assertThat(session.isOpenAt(istanbul(MONDAY, 17, 29))).isFalse();
		assertThat(session.isOpenAt(istanbul(MONDAY, 17, 30))).isTrue();
		assertThat(session.isOpenAt(istanbul(MONDAY, 20, 59))).isTrue();
		assertThat(session.isOpenAt(istanbul(MONDAY, 21, 0))).isFalse();
	}

	@Test
	void aSessionWithoutATimeIsOpenTheWholeDayInIstanbul() {
		AttendanceSessions.Session session = new AttendanceSessions.Session(1, AttendanceSessionKind.WORKSHOP,
				MONDAY, null, null, null);

		assertThat(session.isOpenAt(istanbul(MONDAY, 0, 0))).isTrue();
		assertThat(session.isOpenAt(istanbul(MONDAY, 23, 59))).isTrue();
		assertThat(session.isOpenAt(istanbul(MONDAY.plusDays(1), 0, 0))).isFalse();
		assertThat(session.isOpenAt(istanbul(MONDAY.minusDays(1), 23, 59))).isFalse();
	}

	@Test
	void aSessionWithOnlyAStartOrAnEndBeforeItsStartStaysOpenToTheEndOfTheDay() {
		AttendanceSessions.Session startOnly = new AttendanceSessions.Session(1, AttendanceSessionKind.LESSON,
				MONDAY, LocalTime.of(13, 0), null, null);
		AttendanceSessions.Session backwards = new AttendanceSessions.Session(1, AttendanceSessionKind.LESSON,
				MONDAY, LocalTime.of(18, 0), LocalTime.of(9, 0), null);

		assertThat(startOnly.opensAt()).isEqualTo(istanbul(MONDAY, 12, 30));
		assertThat(startOnly.closesAt()).isEqualTo(istanbul(MONDAY.plusDays(1), 0, 0));
		assertThat(backwards.closesAt()).isEqualTo(istanbul(MONDAY.plusDays(1), 0, 0));
	}

	@Test
	void aSessionWithOnlyAnEndOpensAtTheStartOfItsDay() {
		AttendanceSessions.Session endOnly = new AttendanceSessions.Session(1, AttendanceSessionKind.LESSON, MONDAY,
				null, LocalTime.of(12, 0), null);

		assertThat(endOnly.opensAt()).isEqualTo(istanbul(MONDAY, 0, 0));
		assertThat(endOnly.closesAt()).isEqualTo(istanbul(MONDAY, 13, 0));
	}

	private static Instant istanbul(LocalDate day, int hour, int minute) {
		return day.atTime(hour, minute).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
	}

	private static ProgramSchedule schedule(ProgramWeek week) {
		return ProgramSchedule.of(PROGRAM_START, PROGRAM_END, List.of(week));
	}

	private static ProgramWeek week(int number, ProgramWeekSession... sessions) {
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setWeekNumber(number);
		week.setTitle("Hafta " + number);
		week.setStartDate(MONDAY);
		week.setEndDate(MONDAY.plusDays(6));
		week.setSessions(new ArrayList<>(List.of(sessions)));
		return week;
	}

	private static ProgramWeekSession session(LocalDate date, LocalTime start, LocalTime end, String title) {
		return new ProgramWeekSession(date, start, end, null, title);
	}
}
