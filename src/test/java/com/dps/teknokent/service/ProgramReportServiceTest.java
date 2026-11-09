package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramSessionsResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

@ExtendWith(MockitoExtension.class)
class ProgramReportServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private AdminOverviewService adminOverviewService;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private MeetingFeedbackRepository meetingFeedbackRepository;
	@Mock
	private AppUserRepository appUserRepository;

	@InjectMocks
	private ProgramReportService service;

	@Test
	void averagesTheMenteesRatingsPerQuestionAndCountsEachScore() {
		Meeting first = meeting(MeetingStatus.COMPLETED, Instant.now());
		Meeting second = meeting(MeetingStatus.COMPLETED, Instant.now());

		var summary = ProgramReportService.summarize(UUID.randomUUID(), 3, List.of(
				feedback(first, 5, 4, 3, 5),
				feedback(second, 4, 4, 0, 2)));

		assertThat(summary.heldMeetingCount()).isEqualTo(3);
		assertThat(summary.ratedMeetingCount()).isEqualTo(2);
		assertThat(summary.feedbackCount()).isEqualTo(2);
		assertThat(summary.questionAverages()).containsExactly(4.5, 4.0, 3.0, 3.5);
		assertThat(summary.average()).isEqualTo(3.9);
		assertThat(summary.distribution()).containsExactly(0, 1, 1, 3, 2);
	}

	@Test
	void hasNoAveragesBeforeAnyoneRates() {
		var summary = ProgramReportService.summarize(UUID.randomUUID(), 4, List.of());

		assertThat(summary.average()).isNull();
		assertThat(summary.questionAverages()).isEmpty();
		assertThat(summary.distribution()).containsExactly(0, 0, 0, 0, 0);
	}

	@Test
	void countsOnlyTheProgramsHeldMeetingsInTheWindowAndAsksForMenteeFeedback() {
		UUID actorId = UUID.randomUUID();
		Program program = new Program();
		program.setId(UUID.randomUUID());
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(program.getId());
		startup.setOrganizationId(UUID.randomUUID());
		LocalDate from = LocalDate.now(MeetingSlotTimes.APP_ZONE).minusDays(10);
		Meeting held = meeting(MeetingStatus.COMPLETED, Instant.now().minus(Duration.ofDays(2)));
		Meeting cancelled = meeting(MeetingStatus.CANCELLED, Instant.now().minus(Duration.ofDays(2)));
		Meeting upcoming = meeting(MeetingStatus.SCHEDULED, Instant.now().plus(Duration.ofDays(2)));
		Meeting beforeWindow = meeting(MeetingStatus.COMPLETED, Instant.now().minus(Duration.ofDays(40)));
		when(programService.requireManagedProgram(actorId, program.getId())).thenReturn(program);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())).thenReturn(List.of(startup));
		when(meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(any()))
				.thenReturn(List.of(upcoming, held, cancelled, beforeWindow));
		when(meetingFeedbackRepository.findByMeetingIdInAndAuthorRole(
				argThat((Collection<UUID> ids) -> ids.size() == 1 && ids.contains(held.getId())), eq(UserRole.MENTEE)))
				.thenReturn(List.of(feedback(held, 4, 4, 4, 4)));

		var ratings = service.meetingRatings(actorId, program.getId(), from);

		assertThat(ratings.heldMeetingCount()).isEqualTo(1);
		assertThat(ratings.ratedMeetingCount()).isEqualTo(1);
		assertThat(ratings.average()).isEqualTo(4.0);
	}

	@Test
	void aCoordinatorCannotReadAnotherCoordinatorsProgram() {
		UUID actorId = UUID.randomUUID();
		UUID programId = UUID.randomUUID();
		when(programService.requireManagedProgram(actorId, programId))
				.thenThrow(new InvalidRequestException("Only the program coordinator can change this program."));

		assertThatThrownBy(() -> service.meetingRatings(actorId, programId, null))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(meetingFeedbackRepository);
	}

	@Test
	void sessionsCountAGroupSessionOnceForTheMentorAndOncePerTeam() {
		UUID actorId = UUID.randomUUID();
		Program program = new Program();
		program.setId(UUID.randomUUID());
		Organization ayse = organization("Ayşe Yılmaz");
		Organization can = organization("Can Demir");
		AppUser mentor = user("Zeynep Mentor", "zeynep@example.edu.tr", null);
		LocalDate from = LocalDate.of(2026, 11, 1);
		LocalDate to = LocalDate.of(2026, 11, 30);
		Instant monday = LocalDate.of(2026, 11, 2).atTime(10, 0).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
		Instant tuesday = monday.plus(Duration.ofDays(1));
		Meeting groupAyse = held(mentor, ayse, monday, 60, MeetingStatus.SCHEDULED);
		Meeting groupCan = held(mentor, can, monday, 60, MeetingStatus.COMPLETED);
		Meeting oneToOne = held(mentor, ayse, tuesday, 30, MeetingStatus.SCHEDULED);
		Meeting cancelledLater = held(mentor, can, tuesday, 45, MeetingStatus.CANCELLED);
		when(programService.requireManagedProgram(actorId, program.getId())).thenReturn(program);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()))
				.thenReturn(List.of(startup(program, ayse), startup(program, can)));
		when(meetingRepository
				.findByOrganizationIdInAndHeldConfirmedAtIsNotNullAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
						any(),
						eq(from.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant()),
						eq(LocalDate.of(2026, 12, 1).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant())))
				.thenReturn(List.of(groupAyse, groupCan, oneToOne, cancelledLater));
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(any())).thenReturn(List.of(
				user("Ayşe Yılmaz", "ayse@example.edu.tr", ayse),
				user("Can Demir", "can@example.edu.tr", can)));

		var report = service.sessions(actorId, program.getId(), from, to);

		assertThat(report.mentors()).containsExactly(new ProgramSessionsResponse.Row(
				mentor.getId(), "Zeynep Mentor", "zeynep@example.edu.tr", 2, 90, 2));
		assertThat(report.students()).containsExactly(
				new ProgramSessionsResponse.Row(ayse.getId(), "Ayşe Yılmaz", "ayse@example.edu.tr", 2, 90, 2),
				new ProgramSessionsResponse.Row(can.getId(), "Can Demir", "can@example.edu.tr", 1, 60, 1));
	}

	@Test
	void sessionsCountAPeerMentoringStudentsPersonalTeamWithTheirOwnProgram() {
		UUID actorId = UUID.randomUUID();
		Program program = new Program();
		program.setId(UUID.randomUUID());
		Organization personal = organization("Mehmet Kaya");
		personal.setPersonal(true);
		AppUser student = user("Mehmet Kaya", "mehmet@odtu-dev.invalid", personal);
		student.setProgramId(program.getId());
		AppUser peerMentor = user("Zeynep Demir", "zeynep@odtu-dev.invalid", null);
		LocalDate from = LocalDate.of(2026, 11, 1);
		LocalDate to = LocalDate.of(2026, 11, 30);
		Instant monday = LocalDate.of(2026, 11, 2).atTime(10, 0).atZone(MeetingSlotTimes.APP_ZONE).toInstant();
		Meeting session = held(peerMentor, personal, monday, 45, MeetingStatus.SCHEDULED);
		when(programService.requireManagedProgram(actorId, program.getId())).thenReturn(program);
		when(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())).thenReturn(List.of());
		when(appUserRepository.findPersonalTeamStudentsOfProgram(program.getId())).thenReturn(List.of(student));
		when(meetingRepository
				.findByOrganizationIdInAndHeldConfirmedAtIsNotNullAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
						eq(java.util.Set.of(personal.getId())), any(), any()))
				.thenReturn(List.of(session));
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(any())).thenReturn(List.of(student));

		var report = service.sessions(actorId, program.getId(), from, to);

		assertThat(report.mentors()).containsExactly(new ProgramSessionsResponse.Row(
				peerMentor.getId(), "Zeynep Demir", "zeynep@odtu-dev.invalid", 1, 45, 1));
		assertThat(report.students()).containsExactly(new ProgramSessionsResponse.Row(
				personal.getId(), "Mehmet Kaya", "mehmet@odtu-dev.invalid", 1, 45, 1));
	}

	@Test
	void sessionsRefuseARangeThatEndsBeforeItStarts() {
		UUID actorId = UUID.randomUUID();
		Program program = new Program();
		program.setId(UUID.randomUUID());
		when(programService.requireManagedProgram(actorId, program.getId())).thenReturn(program);

		assertThatThrownBy(() -> service.sessions(
				actorId, program.getId(), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 1)))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static Organization organization(String name) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		return organization;
	}

	private static AppUser user(String name, String email, Organization organization) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setEmail(email);
		user.setOrganization(organization);
		return user;
	}

	private static ProgramStartup startup(Program program, Organization organization) {
		ProgramStartup startup = new ProgramStartup();
		startup.setProgramId(program.getId());
		startup.setOrganizationId(organization.getId());
		return startup;
	}

	private static Meeting held(AppUser mentor, Organization team, Instant at, int minutes, MeetingStatus status) {
		Meeting meeting = meeting(status, at);
		meeting.setMentor(mentor);
		meeting.setOrganization(team);
		meeting.setDurationMinutes(minutes);
		meeting.setHeldConfirmedAt(at.plus(Duration.ofHours(1)));
		return meeting;
	}

	private static Meeting meeting(MeetingStatus status, Instant scheduledAt) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setStatus(status);
		meeting.setScheduledAt(scheduledAt);
		return meeting;
	}

	private static MeetingFeedback feedback(Meeting meeting, Integer... ratings) {
		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setMeeting(meeting);
		feedback.getRatings().addAll(List.of(ratings));
		return feedback;
	}
}
