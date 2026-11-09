package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class AchievementBackfillTest {

	@Mock
	private MeetingRepository meetings;
	@Mock
	private MeetingFeedbackRepository feedback;
	@Mock
	private AchievementService achievements;

	@InjectMocks
	private AchievementBackfill backfill;

	@Test
	void checksEveryMentorAndMenteeOnceSilentlyAndKeepsGoingPastAFailure() {
		UUID mentor = UUID.randomUUID();
		UUID ratedMentor = UUID.randomUUID();
		UUID mentee = UUID.randomUUID();
		when(meetings.findMentorIdsWithHeldMeetings()).thenReturn(List.of(mentor));
		when(feedback.findRatedMentorIds()).thenReturn(List.of(mentor, ratedMentor));
		when(feedback.findAuthorIds()).thenReturn(List.of(mentee));
		when(achievements.evaluate(mentor, false)).thenReturn(List.of(BadgeCode.FIRST_SESSION, BadgeCode.HOURS_25));
		when(achievements.evaluate(ratedMentor, false)).thenThrow(new IllegalStateException("boom"));
		when(achievements.evaluate(mentee, false)).thenReturn(List.of(BadgeCode.ACTIVE_MENTEE));

		assertThat(backfill.run()).isEqualTo(3);
		verify(achievements).evaluate(mentor, false);
		verify(achievements).evaluate(mentee, false);
	}
}
