package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipWorkspaceTab;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import(MentorshipRequestWorkspaceQuery.class)
class MentorshipRequestWorkspaceQueryTest {

	@Autowired
	private MentorshipRequestWorkspaceQuery workspaceQuery;

	@Autowired
	private EntityManager entityManager;

	private Organization organization;
	private AppUser mentor;
	private AppUser mentee;

	@BeforeEach
	void setUp() {
		organization = new Organization();
		organization.setName("Teknokent");
		entityManager.persist(organization);

		mentor = new AppUser();
		mentor.setEmail("mentor-page@tekno.com");
		mentor.setFullName("Mentor");
		mentor.setRole(UserRole.MENTOR);
		entityManager.persist(mentor);

		mentee = new AppUser();
		mentee.setEmail("mentee-page@tekno.com");
		mentee.setFullName("Mentee");
		mentee.setRole(UserRole.MENTEE);
		mentee.setOrganization(organization);
		entityManager.persist(mentee);

		entityManager.flush();
	}

	@Test
	void requestsTab_paginatesWithDatabaseLimit() {
		MentorshipRequest first = persistRequest("A", MentorshipRequestStatus.PENDING, Instant.parse("2026-09-11T10:00:00Z"));
		MentorshipRequest second = persistRequest("B", MentorshipRequestStatus.PENDING, Instant.parse("2026-09-10T10:00:00Z"));
		MentorshipRequest third = persistRequest("C", MentorshipRequestStatus.PENDING, Instant.parse("2026-09-09T10:00:00Z"));
		entityManager.flush();
		entityManager.createNativeQuery("UPDATE mentorship_request SET created_at = ? WHERE id = ?")
				.setParameter(1, java.sql.Timestamp.from(Instant.parse("2026-09-11T10:00:00Z")))
				.setParameter(2, first.getId())
				.executeUpdate();
		entityManager.createNativeQuery("UPDATE mentorship_request SET created_at = ? WHERE id = ?")
				.setParameter(1, java.sql.Timestamp.from(Instant.parse("2026-09-10T10:00:00Z")))
				.setParameter(2, second.getId())
				.executeUpdate();
		entityManager.createNativeQuery("UPDATE mentorship_request SET created_at = ? WHERE id = ?")
				.setParameter(1, java.sql.Timestamp.from(Instant.parse("2026-09-09T10:00:00Z")))
				.setParameter(2, third.getId())
				.executeUpdate();
		entityManager.clear();

		var page = workspaceQuery.findPage(
				null,
				organization.getId(),
				MentorshipWorkspaceTab.REQUESTS,
				PageRequest.of(0, 2),
				Instant.parse("2026-09-11T12:00:00Z"));

		assertThat(page.getTotalElements()).isEqualTo(3);
		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getContent().get(0).getId()).isEqualTo(first.getId());
		assertThat(page.getContent().get(1).getId()).isEqualTo(second.getId());
	}

	@Test
	void activeTab_onlyReturnsAcceptedWithOpenMeeting() {
		MentorshipRequest active = persistRequest("Active", MentorshipRequestStatus.ACCEPTED, Instant.parse("2026-09-11T09:00:00Z"));
		MentorshipRequest ended = persistRequest("Ended", MentorshipRequestStatus.ACCEPTED, Instant.parse("2026-09-10T09:00:00Z"));
		persistMeeting(active, MeetingStatus.SCHEDULED, Instant.parse("2026-09-11T11:30:00Z"), 60);
		persistMeeting(ended, MeetingStatus.COMPLETED, Instant.parse("2026-09-10T10:00:00Z"), 45);
		entityManager.flush();
		entityManager.clear();

		Instant now = Instant.parse("2026-09-11T12:00:00Z");
		var page = workspaceQuery.findPage(
				mentor.getId(),
				null,
				MentorshipWorkspaceTab.ACTIVE,
				PageRequest.of(0, 7),
				now);

		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().getFirst().getId()).isEqualTo(active.getId());
	}

	private MentorshipRequest persistRequest(
			String supportArea, MentorshipRequestStatus status, Instant createdAt) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(mentee);
		request.setOrganization(organization);
		request.setMentor(mentor);
		request.setRequestedForName("Mentee");
		request.setSupportArea(supportArea);
		request.setSummary(supportArea);
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(status);
		request.setCreatedAt(createdAt);
		request.setUpdatedAt(createdAt);
		entityManager.persist(request);
		return request;
	}

	private void persistMeeting(
			MentorshipRequest request,
			MeetingStatus status,
			Instant scheduledAt,
			int durationMinutes) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(organization);
		meeting.setMentor(mentor);
		meeting.setMentorshipRequest(request);
		meeting.setScheduledAt(scheduledAt);
		meeting.setDurationMinutes(durationMinutes);
		meeting.setStatus(status);
		meeting.setTranscribeRequested(false);
		entityManager.persist(meeting);
	}
}
