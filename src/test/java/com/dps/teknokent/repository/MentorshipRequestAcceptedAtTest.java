package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

/** The mentorship's start date is stamped on save, whichever path accepted the request. */
@DataJpaTest
class MentorshipRequestAcceptedAtTest {

	@Autowired
	private EntityManager entityManager;

	private Organization team;
	private AppUser mentor;
	private AppUser mentee;

	@BeforeEach
	void setUp() {
		team = new Organization();
		team.setName("Kişisel");
		entityManager.persist(team);
		mentor = person("mentor-accepted@odtu-dev.invalid", UserRole.MENTOR);
		mentee = person("mentee-accepted@odtu-dev.invalid", UserRole.MENTEE);
		entityManager.persist(mentor);
		entityManager.persist(mentee);
	}

	@Test
	void anOpenRequestHasNoStartUntilTheMentorAccepts() {
		MentorshipRequest request = pending();
		assertThat(reload(request).getAcceptedAt()).isNull();

		Instant before = Instant.now();
		MentorshipRequest loaded = reload(request);
		loaded.recordDecision(MentorshipRequestStatus.ACCEPTED, "Görüşelim");

		assertThat(reload(loaded).getAcceptedAt()).isNotNull().isAfterOrEqualTo(before.minusSeconds(1));
	}

	@Test
	void aCoordinatorsMatchSettingTheStatusDirectlyIsStampedToo() {
		MentorshipRequest loaded = reload(pending());
		loaded.setStatus(MentorshipRequestStatus.ACCEPTED);

		assertThat(reload(loaded).getAcceptedAt()).isNotNull();
	}

	@Test
	void aMeetingMadeFromTheCalendarStartsTheMentorshipWhenSaved() {
		MentorshipRequest request = MentorshipRequest.forCalendarMeeting(mentee, team, mentor, "Haftalık görüşme");
		entityManager.persist(request);

		assertThat(reload(request).getAcceptedAt()).isNotNull();
	}

	@Test
	void theStartIsKeptWhenTheRequestChangesLater() {
		MentorshipRequest loaded = reload(pending());
		loaded.recordDecision(MentorshipRequestStatus.ACCEPTED, "Görüşelim");
		Instant started = reload(loaded).getAcceptedAt();

		MentorshipRequest again = reload(loaded);
		again.setSummary("Konu güncellendi");
		again.setStatus(MentorshipRequestStatus.WITHDRAWN);

		assertThat(reload(again).getAcceptedAt()).isEqualTo(started);
	}

	@Test
	void aDeclinedRequestNeverStarts() {
		MentorshipRequest loaded = reload(pending());
		loaded.recordDecision(MentorshipRequestStatus.DECLINED, "Uygun değilim");

		assertThat(reload(loaded).getAcceptedAt()).isNull();
	}

	private MentorshipRequest pending() {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(mentee);
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setRequestedForName("Mentee");
		request.setSupportArea("CAREER_INTERNSHIP");
		request.setSummary("Staj başvurusu");
		request.setUrgency(MentorshipRequestUrgency.NOT_URGENT);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.NO_PREFERENCE);
		request.setStatus(MentorshipRequestStatus.PENDING);
		entityManager.persist(request);
		return request;
	}

	private MentorshipRequest reload(MentorshipRequest request) {
		entityManager.flush();
		entityManager.clear();
		return entityManager.find(MentorshipRequest.class, request.getId());
	}

	private static AppUser person(String email, UserRole role) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		return user;
	}
}
