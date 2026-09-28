package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.dto.responses.MentorRatingResponse;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository.MentorshipRating;

@ExtendWith(MockitoExtension.class)
class MentorRatingServiceTest {

	@Mock
	private MeetingFeedbackRepository feedbackRepository;

	@InjectMocks
	private MentorRatingService service;

	private final UUID mentorId = UUID.randomUUID();

	@Test
	void weighsEachMentorshipByItsFeedbackAndKeepsUnlinkedMeetingsInTheTotalOnly() {
		UUID requestId = UUID.randomUUID();
		when(feedbackRepository.ratingsByMentorship(mentorId)).thenReturn(List.of(
				row(requestId, 5.0, 3), row(null, 3.0, 1)));

		MentorRatingResponse rating = service.rating(mentorId);

		assertThat(rating.average()).isEqualTo(4.5);
		assertThat(rating.feedbackCount()).isEqualTo(4);
		assertThat(rating.mentorships()).singleElement().satisfies(mentorship -> {
			assertThat(mentorship.mentorshipRequestId()).isEqualTo(requestId);
			assertThat(mentorship.average()).isEqualTo(5.0);
		});
	}

	@Test
	void hasNoAverageBeforeTheFirstFeedback() {
		when(feedbackRepository.ratingsByMentorship(mentorId)).thenReturn(List.of());

		MentorRatingResponse rating = service.rating(mentorId);

		assertThat(rating.average()).isNull();
		assertThat(rating.feedbackCount()).isZero();
	}

	private static MentorshipRating row(UUID requestId, double average, long feedback) {
		return new MentorshipRating() {
			@Override
			public UUID getRequestId() {
				return requestId;
			}

			@Override
			public Double getAverage() {
				return average;
			}

			@Override
			public Long getFeedbackCount() {
				return feedback;
			}
		};
	}
}
