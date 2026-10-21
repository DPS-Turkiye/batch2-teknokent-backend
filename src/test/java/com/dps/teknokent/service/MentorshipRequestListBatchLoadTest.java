package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.MentorshipRequestMessageResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

/**
 * The phone's Mesajlar screen polls the request list every 15 s, so its messages and recommendation snapshots
 * must load once for the whole list, not once per request.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MentorshipRequestListBatchLoadTest {

	private static final int REQUESTS = 6;

	@Autowired
	private MentorshipRequestService mentorshipRequestService;

	@Autowired
	private EntityManager entityManager;

	@Test
	void listLoadsMessagesAndSnapshotsOnceForAllRequests() {
		Organization organization = new Organization();
		organization.setName("Teknokent");
		entityManager.persist(organization);
		AppUser mentee = persistUser("mentee-list@tekno.com", UserRole.MENTEE, organization);
		AppUser mentor = persistUser("mentor-list@tekno.com", UserRole.MENTOR, null);
		List<MentorshipRequest> requests = new ArrayList<>();
		for (int i = 0; i < REQUESTS; i++) {
			MentorshipRequest request = persistRequest(mentee, organization, mentor, Instant.parse("2026-10-20T10:00:00Z").plusSeconds(i));
			persistMessage(request, mentee, "first " + i, Instant.parse("2026-10-20T11:00:00Z"));
			persistMessage(request, mentor, "second " + i, Instant.parse("2026-10-20T12:00:00Z"));
			requests.add(request);
		}
		persistRun(requests.getFirst(), "Fintech");
		entityManager.flush();
		entityManager.clear();

		Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		List<MentorshipRequestResponse> responses = mentorshipRequestService.findAll(null, null, organization.getId());

		assertThat(responses).hasSize(REQUESTS);
		for (MentorshipRequestResponse response : responses) {
			int index = requests.stream().map(MentorshipRequest::getId).toList().indexOf(response.id());
			assertThat(response.messages()).extracting(MentorshipRequestMessageResponse::body)
					.containsExactly("first " + index, "second " + index);
			if (index == 0) {
				assertThat(response.recommendationSnapshot().startupSector()).isEqualTo("Fintech");
			} else {
				assertThat(response.recommendationSnapshot()).isNull();
			}
		}
		// List, messages, runs, two users, organization, and the one run's two collections: the same for any size.
		assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8);
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

	private MentorshipRequest persistRequest(AppUser mentee, Organization organization, AppUser mentor, Instant createdAt) {
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
		request.setCreatedAt(createdAt);
		request.setUpdatedAt(createdAt);
		entityManager.persist(request);
		return request;
	}

	private void persistMessage(MentorshipRequest request, AppUser author, String body, Instant createdAt) {
		MentorshipRequestMessage message = MentorshipRequestMessage.create(
				request, author, MentorshipRequestMessageType.MENTEE_MESSAGE, body);
		message.setCreatedAt(createdAt);
		entityManager.persist(message);
	}

	private void persistRun(MentorshipRequest request, String sector) {
		MentorshipRequestRecommendationRun run = new MentorshipRequestRecommendationRun();
		run.setRequest(request);
		run.setMatchingMethod("PROFILE");
		run.setGeneratedAt(Instant.parse("2026-10-20T10:30:00Z"));
		run.setStartupSector(sector);
		entityManager.persist(run);
	}
}
