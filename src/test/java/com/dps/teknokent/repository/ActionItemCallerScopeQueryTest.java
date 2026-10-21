package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

@DataJpaTest
class ActionItemCallerScopeQueryTest {

	@Autowired
	private ActionItemRepository actionItemRepository;
	@Autowired
	private EntityManager entityManager;

	private Organization ownStartup;
	private AppUser ownMentor;
	private MentorshipRequest own;
	private MentorshipRequest otherStartups;
	private MentorshipRequest otherMentors;

	@BeforeEach
	void setUp() {
		ownStartup = organization("Lumen");
		Organization otherStartup = organization("Atlas");
		ownMentor = user("scope-mentor@tekno.com", UserRole.MENTOR, null);
		AppUser otherMentor = user("scope-other-mentor@tekno.com", UserRole.MENTOR, null);
		AppUser mentee = user("scope-mentee@tekno.com", UserRole.MENTEE, ownStartup);
		AppUser otherMentee = user("scope-other-mentee@tekno.com", UserRole.MENTEE, otherStartup);
		own = request(mentee, ownStartup, ownMentor);
		otherStartups = request(otherMentee, otherStartup, ownMentor);
		otherMentors = request(mentee, ownStartup, otherMentor);
		item(own, mentee);
		item(otherStartups, otherMentee);
		item(otherMentors, mentee);
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	void aStartupGetsOnlyItsOwnRequestsItemsWhateverIdsItSends() {
		List<ActionItem> items = actionItemRepository
				.findByMentorshipRequestIdInAndMentorshipRequestOrganizationIdOrderByCreatedAtDesc(
						List.of(own.getId(), otherStartups.getId(), otherMentors.getId()), ownStartup.getId());

		assertThat(items).extracting(item -> item.getMentorshipRequest().getId())
				.containsExactlyInAnyOrder(own.getId(), otherMentors.getId());
	}

	@Test
	void aMentorGetsOnlyTheirOwnRequestsItemsWhateverIdsTheySend() {
		List<ActionItem> items = actionItemRepository
				.findByMentorshipRequestIdInAndMentorshipRequestMentorIdOrderByCreatedAtDesc(
						List.of(own.getId(), otherStartups.getId(), otherMentors.getId()), ownMentor.getId());

		assertThat(items).extracting(item -> item.getMentorshipRequest().getId())
				.containsExactlyInAnyOrder(own.getId(), otherStartups.getId());
	}

	private Organization organization(String name) {
		Organization organization = new Organization();
		organization.setName(name);
		entityManager.persist(organization);
		return organization;
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

	private MentorshipRequest request(AppUser requester, Organization organization, AppUser mentor) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(requester);
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

	private void item(MentorshipRequest request, AppUser owner) {
		ActionItem item = new ActionItem();
		item.setOrganization(request.getOrganization());
		item.setMentorshipRequest(request);
		item.setOwner(owner);
		item.setTitle("Rakip analizi");
		item.setStatus(ActionItemStatus.OPEN);
		item.setCreatedAt(Instant.parse("2026-09-02T10:00:00Z"));
		item.setUpdatedAt(Instant.parse("2026-09-02T10:00:00Z"));
		entityManager.persist(item);
	}
}
