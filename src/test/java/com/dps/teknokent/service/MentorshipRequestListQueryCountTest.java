package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

/**
 * The phone's Mesajlar screen polls the request list every 15 s. Each request reads its mentor, requester and
 * organization; those lazy loads must be batched, not one query per mentor.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MentorshipRequestListQueryCountTest {

	private static final int REQUESTS = 6;

	@Autowired
	private MentorshipRequestService mentorshipRequestService;

	@Autowired
	private EntityManager entityManager;

	@Test
	void listLoadsMentorsRequesterAndOrganizationInBatches() {
		Organization organization = new Organization();
		organization.setName("Teknokent");
		entityManager.persist(organization);
		AppUser mentee = persistUser("mentee-batch@tekno.com", UserRole.MENTEE, organization);
		for (int i = 0; i < REQUESTS; i++) {
			persistRequest(mentee, organization, persistUser("mentor-batch-" + i + "@tekno.com", UserRole.MENTOR, null));
		}
		entityManager.flush();
		entityManager.clear();

		Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		var requests = mentorshipRequestService.findAll(null, null, organization.getId());

		assertThat(requests).hasSize(REQUESTS);
		// The list, then messages and the recommendation run per request, then one batch of users and one organization.
		assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(1 + 2L * REQUESTS + 2);
	}

	private AppUser persistUser(String email, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		user.setOrganization(organization);
		entityManager.persist(user);
		return user;
	}

	private void persistRequest(AppUser mentee, Organization organization, AppUser mentor) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(mentee);
		request.setOrganization(organization);
		request.setMentor(mentor);
		request.setRequestedForName("Mentee");
		request.setSupportArea("Product");
		request.setSummary("Product");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.PENDING);
		request.setCreatedAt(Instant.parse("2026-10-20T10:00:00Z"));
		request.setUpdatedAt(Instant.parse("2026-10-20T10:00:00Z"));
		entityManager.persist(request);
	}
}
