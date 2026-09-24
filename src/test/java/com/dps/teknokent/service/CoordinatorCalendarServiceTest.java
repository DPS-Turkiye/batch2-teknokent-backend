package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.CoordinatorAgendaKind;
import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorMentorshipResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.service.CoordinatorProgramService.Snapshot;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class CoordinatorCalendarServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private CoordinatorMentorshipService mentorshipService;
	@Mock
	private ProgramEventRepository programEventRepository;
	@InjectMocks
	private CoordinatorCalendarService service;

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

	private final AppUser coordinator = user("Elif Demir", UserRole.COORDINATOR);
	private final AppUser ahmet = user("Ahmet Can", UserRole.MENTOR);
	private final AppUser ayse = user("Ayşe Demir", UserRole.MENTOR);
	private final Organization nova = organization("Nova Robotics");
	private final Organization vega = organization("Vega");
	private final Organization lumen = organization("Lumen");
	private final Program program = program();

	@Test
	void overlappingMeetingsOfOneMentorAreAConflictButBackToBackOnesAreNot() {
		Meeting first = meeting(ahmet, nova, at(MONDAY.plusDays(2), 11, 0));
		Meeting overlapping = meeting(ahmet, vega, at(MONDAY.plusDays(2), 11, 30));
		Meeting backToBack = meeting(ahmet, nova, at(MONDAY.plusDays(3), 10, 0));
		Meeting afterIt = meeting(ahmet, vega, at(MONDAY.plusDays(3), 11, 0));
		Meeting otherMentor = meeting(ayse, lumen, at(MONDAY.plusDays(2), 11, 15));

		var conflicts = CoordinatorCalendarService.conflicts(List.of(first, overlapping, backToBack, afterIt, otherMentor));

		assertThat(conflicts).singleElement().satisfies(conflict -> {
			assertThat(conflict.mentorName()).isEqualTo("Ahmet Can");
			assertThat(conflict.meetingIds()).containsExactly(first.getId(), overlapping.getId());
		});
	}

	@Test
	void aRangeLongerThanTwoMonthsOrBackwardsIsRejected() {
		assertThatThrownBy(() -> service.calendar(COORDINATOR_ID, null, MONDAY, MONDAY.minusDays(1)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.calendar(COORDINATOR_ID, null, MONDAY, MONDAY.plusDays(62)))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(programService);
	}

	@Test
	void theWeekHoldsMeetingsInRangeWithEventsDeadlinesAndTheRiskyPairsLeftUnscheduled() {
		Meeting novaMeeting = meeting(ahmet, nova, at(MONDAY.plusDays(2), 11, 0));
		Meeting vegaMeeting = meeting(ahmet, vega, at(MONDAY.plusDays(2), 11, 30));
		Meeting earlier = meeting(ahmet, nova, at(MONDAY.minusDays(14), 11, 0));
		Meeting cancelled = meeting(ayse, lumen, at(MONDAY.plusDays(1), 9, 0));
		cancelled.setStatus(MeetingStatus.CANCELLED);
		Meeting nextWeek = meeting(ayse, lumen, at(MONDAY.plusDays(8), 9, 0));
		stubScope(List.of(novaMeeting, vegaMeeting, earlier, cancelled, nextWeek));
		UUID novaMentorshipId = UUID.randomUUID();
		when(mentorshipService.list(COORDINATOR_ID, null)).thenReturn(List.of(
				row(novaMentorshipId, ahmet, nova, "Teknik mimari", CoordinatorMentorshipHealth.FOLLOW_UP, Instant.now()),
				row(UUID.randomUUID(), ayse, lumen, "Finansal model", CoordinatorMentorshipHealth.RISKY, null)));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(eq(Set.of(PROGRAM_ID)), any(), any()))
				.thenReturn(List.of(
						event("Ürün-Pazar Uyumu Atölyesi", ProgramEventKind.EVENT, at(MONDAY.plusDays(1), 16, 0)),
						event("Ara rapor teslimi", ProgramEventKind.DEADLINE, at(MONDAY.plusDays(3), 23, 59))));

		var calendar = service.calendar(COORDINATOR_ID, null, MONDAY, MONDAY.plusDays(6));

		assertThat(calendar.items()).extracting(item -> item.kind()).containsExactly(
				CoordinatorAgendaKind.EVENT,
				CoordinatorAgendaKind.MEETING,
				CoordinatorAgendaKind.MEETING,
				CoordinatorAgendaKind.DEADLINE);
		var novaItem = calendar.items().get(1);
		assertThat(novaItem.mentorshipId()).isEqualTo(novaMentorshipId);
		assertThat(novaItem.topic()).isEqualTo("Teknik mimari");
		assertThat(novaItem.sequence()).isEqualTo(2);
		assertThat(novaItem.conflict()).isTrue();
		assertThat(novaItem.endsAt()).isEqualTo(novaItem.startsAt().plusSeconds(3600));
		assertThat(calendar.conflicts()).hasSize(1);
		assertThat(calendar.summary().meetings()).isEqualTo(2);
		assertThat(calendar.summary().events()).isEqualTo(1);
		assertThat(calendar.summary().deadlines()).isEqualTo(1);
		assertThat(calendar.summary().unscheduledRisky()).isEqualTo(1);
	}

	private void stubScope(List<Meeting> meetings) {
		when(programService.requireCoordinatorOrAdmin(COORDINATOR_ID)).thenReturn(coordinator);
		when(programService.managedPrograms(coordinator)).thenReturn(List.of(program));
		when(programService.scopeOf(COORDINATOR_ID, List.of(program), null)).thenReturn(List.of(program));
		Snapshot snapshot = new Snapshot(
				MONDAY,
				at(MONDAY, 9, 0),
				Map.of(),
				Map.of(),
				Map.of(PROGRAM_ID, List.of(startup(nova), startup(vega), startup(lumen))),
				Map.of(),
				Map.of(),
				Map.of(),
				Map.of(),
				meetings.stream().collect(Collectors.groupingBy(meeting -> meeting.getOrganization().getId())),
				Map.of(),
				Set.of());
		when(programService.snapshot(List.of(program))).thenReturn(snapshot);
	}

	private static CoordinatorMentorshipResponse row(
			UUID id,
			AppUser mentor,
			Organization organization,
			String topic,
			CoordinatorMentorshipHealth health,
			Instant next) {
		return new CoordinatorMentorshipResponse(
				id, PROGRAM_ID, "METU Stars 2026", mentor.getId(), mentor.getFullName(), null,
				organization.getId(), organization.getName(), MentorshipStatus.ACTIVE, health, topic,
				null, null, null, next, 0, 0, 0);
	}

	private static Instant at(LocalDate day, int hour, int minute) {
		return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
	}

	private static Meeting meeting(AppUser mentor, Organization organization, Instant at) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setOrganization(organization);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(60);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	private static ProgramEvent event(String title, ProgramEventKind kind, Instant startsAt) {
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(PROGRAM_ID);
		event.setTitle(title);
		event.setKind(kind);
		event.setStartsAt(startsAt);
		return event;
	}

	private static ProgramStartup startup(Organization organization) {
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(PROGRAM_ID);
		startup.setOrganizationId(organization.getId());
		return startup;
	}

	private static Organization organization(String name) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		return organization;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		program.setCreatedBy(COORDINATOR_ID);
		return program;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		return user;
	}
}
