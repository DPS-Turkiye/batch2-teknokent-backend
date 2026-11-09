package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AchievementProgressResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.UserBadgeRepository;

@ExtendWith(MockitoExtension.class)
class AchievementServiceTest {

	private static final Instant MONDAY = Instant.parse("2026-11-02T10:00:00Z");

	@Mock
	private MeetingRepository meetings;
	@Mock
	private MeetingFeedbackRepository feedback;
	@Mock
	private UserBadgeRepository awards;
	@Mock
	private AppUserRepository users;
	@Mock
	private BadgeService badgeService;

	@InjectMocks
	private AchievementService service;

	private final AppUser mentor = user("Deniz Mentor", UserRole.MENTEE);
	private final AppUser mentee = user("Ece Öğrenci", UserRole.MENTEE);

	@BeforeEach
	void people() {
		lenient().when(users.findById(mentor.getId())).thenReturn(Optional.of(mentor));
		lenient().when(users.findById(mentee.getId())).thenReturn(Optional.of(mentee));
	}

	@Test
	void theFirstSessionTheMentorConfirmedEarnsIlkGorusme() {
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId()))
				.thenReturn(List.of(held(MONDAY, 60)));

		assertThat(service.evaluate(mentor.getId(), true)).containsExactly(BadgeCode.FIRST_SESSION);
		verify(badgeService).award(mentor, BadgeCode.FIRST_SESSION, null, BadgeSource.RULE, null, true);
	}

	@Test
	void aGroupSessionCountsOnceAndACancelledSeatNotAtAll() {
		List<Meeting> nine = new ArrayList<>();
		for (int week = 0; week < 9; week++) {
			nine.add(held(MONDAY.plus(week * 7L, ChronoUnit.DAYS), 60));
		}
		nine.add(held(MONDAY, 60));
		Meeting cancelled = held(MONDAY.minus(1, ChronoUnit.DAYS), 60);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		nine.add(cancelled);
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId())).thenReturn(nine);

		assertThat(service.progressOf(mentor.getId()).sessions()).isEqualTo(9);
		assertThat(service.evaluate(mentor.getId(), true)).doesNotContain(BadgeCode.SESSIONS_10);

		nine.add(held(MONDAY.plus(70, ChronoUnit.DAYS), 60));
		assertThat(service.evaluate(mentor.getId(), true)).contains(BadgeCode.SESSIONS_10);
	}

	@Test
	void twentyFiveHoursUseTheLongestPlannedLengthOfEachSession() {
		List<Meeting> sessions = new ArrayList<>();
		for (int week = 0; week < 12; week++) {
			sessions.add(held(MONDAY.plus(week * 7L, ChronoUnit.DAYS), 120));
		}
		sessions.add(held(MONDAY, 30));
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId())).thenReturn(sessions);
		assertThat(service.progressOf(mentor.getId()).minutes()).isEqualTo(24 * 60);
		assertThat(service.evaluate(mentor.getId(), true)).doesNotContain(BadgeCode.HOURS_25);

		sessions.add(held(MONDAY.plus(100, ChronoUnit.DAYS), 60));
		assertThat(service.evaluate(mentor.getId(), true)).contains(BadgeCode.HOURS_25);
	}

	@Test
	void wellRatedNeedsFiveFeedbacksAveragingAtLeastFourAndAHalf() {
		List<MeetingFeedback> four = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			four.add(rating(mentee, held(MONDAY, 60), 5, 5, 5, 5));
		}
		when(feedback.findByMeetingMentorIdAndAuthorRole(mentor.getId(), UserRole.MENTEE)).thenReturn(four);
		assertThat(service.evaluate(mentor.getId(), true)).doesNotContain(BadgeCode.WELL_RATED);

		four.add(rating(mentee, held(MONDAY, 60), 4, 4, 4, 3));
		assertThat(service.progressOf(mentor.getId()).average()).isEqualTo(4.75);
		assertThat(service.evaluate(mentor.getId(), true)).contains(BadgeCode.WELL_RATED);
	}

	@Test
	void aLowAverageOrFeedbackOnACancelledMeetingDoesNotCount() {
		List<MeetingFeedback> items = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			items.add(rating(mentee, held(MONDAY, 60), 4, 4, 5, 4));
		}
		Meeting cancelled = held(MONDAY, 60);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		items.add(rating(mentee, cancelled, 5, 5, 5, 5));
		when(feedback.findByMeetingMentorIdAndAuthorRole(mentor.getId(), UserRole.MENTEE)).thenReturn(items);

		assertThat(service.progressOf(mentor.getId()).ratedFeedback()).isEqualTo(5);
		assertThat(service.evaluate(mentor.getId(), true)).doesNotContain(BadgeCode.WELL_RATED);
	}

	@Test
	void anActiveMenteeGaveFeedbackOnThreeSessionsTheMentorConfirmed() {
		Meeting notConfirmed = held(MONDAY, 60);
		notConfirmed.setHeldConfirmedAt(null);
		List<MeetingFeedback> given = new ArrayList<>(List.of(
				rating(mentee, held(MONDAY, 60), 5),
				rating(mentee, held(MONDAY.plus(7, ChronoUnit.DAYS), 60), 4),
				rating(mentee, notConfirmed, 5)));
		when(feedback.findByAuthorId(mentee.getId())).thenReturn(given);
		assertThat(service.evaluate(mentee.getId(), true)).isEmpty();

		given.add(rating(mentee, held(MONDAY.plus(14, ChronoUnit.DAYS), 60), 3));
		assertThat(service.evaluate(mentee.getId(), true)).containsExactly(BadgeCode.ACTIVE_MENTEE);
	}

	@Test
	void aBadgeAlreadyAwardedOrRevokedIsNotAwardedAgain() {
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId()))
				.thenReturn(List.of(held(MONDAY, 60)));
		when(awards.existsByUserIdAndBadgeCode(mentor.getId(), BadgeCode.FIRST_SESSION)).thenReturn(true);

		assertThat(service.evaluate(mentor.getId(), true)).isEmpty();
		verify(badgeService, never()).award(any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void aFailingCheckForOnePersonDoesNotStopTheOthers() {
		UUID broken = UUID.randomUUID();
		when(users.findById(broken)).thenThrow(new IllegalStateException("boom"));
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId()))
				.thenReturn(List.of(held(MONDAY, 60)));

		service.onChange(new AchievementsMayChangeEvent(new LinkedHashSet<>(List.of(broken, mentor.getId()))));

		verify(badgeService).award(eq(mentor), eq(BadgeCode.FIRST_SESSION), isNull(), eq(BadgeSource.RULE), isNull(),
				eq(true));
	}

	@Test
	void rozetlerimShowsEachAchievementWithItsProgress() {
		List<Meeting> four = new ArrayList<>();
		for (int week = 0; week < 4; week++) {
			four.add(held(MONDAY.plus(week * 7L, ChronoUnit.DAYS), 90));
		}
		when(meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(mentor.getId())).thenReturn(four);
		UserBadge first = new UserBadge();
		first.setBadgeCode(BadgeCode.FIRST_SESSION);
		first.setAwardedAt(MONDAY);
		when(awards.findByUserIdAndStatusOrderByAwardedAtDesc(mentor.getId(), BadgeStatus.ACTIVE))
				.thenReturn(List.of(first));

		List<AchievementProgressResponse> rows = service.mine(mentor.getId());

		assertThat(rows).extracting(AchievementProgressResponse::code).containsExactly(BadgeCode.FIRST_SESSION,
				BadgeCode.SESSIONS_10, BadgeCode.HOURS_25, BadgeCode.WELL_RATED, BadgeCode.ACTIVE_MENTEE);
		assertThat(rows.get(0).earned()).isTrue();
		assertThat(rows.get(0).awardedAt()).isEqualTo(MONDAY);
		assertThat(rows.get(1)).isEqualTo(new AchievementProgressResponse(BadgeCode.SESSIONS_10, false, null, 4, 10,
				null, null));
		assertThat(rows.get(2).current()).isEqualTo(360);
		assertThat(rows.get(2).target()).isEqualTo(1500);
		assertThat(rows.get(3).averageTarget()).isEqualTo(4.5);
	}

	private Meeting held(Instant at, int minutes) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentor(mentor);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(minutes);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		meeting.setHeldConfirmedAt(at.plus(1, ChronoUnit.HOURS));
		return meeting;
	}

	private static MeetingFeedback rating(AppUser author, Meeting meeting, Integer... ratings) {
		MeetingFeedback item = new MeetingFeedback();
		item.setId(UUID.randomUUID());
		item.setAuthor(author);
		item.setMeeting(meeting);
		item.getRatings().addAll(List.of(ratings));
		return item;
	}

	private static AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		user.setRole(role);
		return user;
	}
}
