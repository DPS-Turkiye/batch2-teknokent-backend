package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.MeetingFeedbackRepository.MentorshipRating;

import jakarta.persistence.EntityManager;

@DataJpaTest
class MeetingFeedbackRatingQueryTest {

	@Autowired
	private MeetingFeedbackRepository feedbackRepository;
	@Autowired
	private EntityManager entityManager;

	private Organization organization;
	private AppUser mentor;
	private AppUser mentee;

	@BeforeEach
	void setUp() {
		organization = new Organization();
		organization.setName("Lumen");
		entityManager.persist(organization);
		mentor = user("rating-mentor@tekno.com", UserRole.MENTOR, null);
		mentee = user("rating-mentee@tekno.com", UserRole.MENTEE, organization);
		entityManager.flush();
	}

	@Test
	void averagesMenteeAnswersPerMentorshipAndIgnoresTheMentorsOwnFeedback() {
		MentorshipRequest first = request();
		MentorshipRequest second = request();
		feedback(meeting(first), mentee, List.of(5, 5, 4, 4));
		feedback(meeting(first), mentee, List.of(3, 3, 3, 3));
		feedback(meeting(second), mentee, List.of(2, 2, 2, 2));
		feedback(meeting(second), mentor, List.of(5, 5, 5, 5));
		entityManager.flush();
		entityManager.clear();

		Map<UUID, MentorshipRating> rows = feedbackRepository.ratingsByMentorship(mentor.getId()).stream()
				.collect(Collectors.toMap(MentorshipRating::getRequestId, row -> row));

		assertThat(rows).hasSize(2);
		assertThat(rows.get(first.getId()).getAverage()).isEqualTo(3.75);
		assertThat(rows.get(first.getId()).getFeedbackCount()).isEqualTo(2);
		assertThat(rows.get(second.getId()).getAverage()).isEqualTo(2.0);
		assertThat(rows.get(second.getId()).getFeedbackCount()).isEqualTo(1);
		assertThat(feedbackRepository.ratingsByMentorship(UUID.randomUUID())).isEmpty();
	}

	private AppUser user(String email, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		user.setOrganization(organization);
		entityManager.persist(user);
		return user;
	}

	private MentorshipRequest request() {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(mentee);
		request.setOrganization(organization);
		request.setMentor(mentor);
		request.setRequestedForName("Mentee");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		request.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
		request.setUpdatedAt(Instant.parse("2026-09-01T10:00:00Z"));
		entityManager.persist(request);
		return request;
	}

	private Meeting meeting(MentorshipRequest request) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(organization);
		meeting.setMentor(mentor);
		meeting.setMentorshipRequest(request);
		meeting.setScheduledAt(Instant.parse("2026-09-10T10:00:00Z"));
		meeting.setDurationMinutes(45);
		meeting.setStatus(MeetingStatus.COMPLETED);
		meeting.setTranscribeRequested(false);
		entityManager.persist(meeting);
		return meeting;
	}

	private void feedback(Meeting meeting, AppUser author, List<Integer> ratings) {
		MeetingFeedback feedback = new MeetingFeedback();
		feedback.setMeeting(meeting);
		feedback.setAuthor(author);
		feedback.setCreatedAt(Instant.parse("2026-09-10T11:00:00Z"));
		feedback.setRatings(new java.util.ArrayList<>(ratings));
		entityManager.persist(feedback);
	}
}
