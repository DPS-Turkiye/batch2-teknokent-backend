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
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
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
