package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.RescheduleMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.NotificationRepository;

import jakarta.persistence.EntityManager;

/**
 * "Yeni saat öner" end to end, with the real services and database: a planned meeting moves only once the mentor
 * and the team lead have both said yes, and only the people in the meeting can take part.
 */
@SpringBootTest
@Transactional
class MeetingRescheduleFlowTest {

	private static final Instant PLANNED = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MINUTES);
	private static final Instant NEW_TIME = PLANNED.plus(Duration.ofDays(1));

	@Autowired
	private MeetingProposalService proposals;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private EntityManager entityManager;

	private AppUser lead;
	private AppUser member;
	private AppUser mentor;
	private AppUser outsider;
	private Meeting meeting;

	@BeforeEach
	void plannedMeetingBetweenATeamAndAMentor() {
		Organization team = new Organization();
		team.setName("Flow Team");
		entityManager.persist(team);
		lead = persistUser("lead-flow@tekno.com", UserRole.MENTEE, team);
		member = persistUser("member-flow@tekno.com", UserRole.MENTEE, team);
		team.setFounder(lead);
		Organization otherTeam = new Organization();
		otherTeam.setName("Other Team");
		entityManager.persist(otherTeam);
		outsider = persistUser("outsider-flow@tekno.com", UserRole.MENTEE, otherTeam);
		mentor = persistUser("mentor-flow@tekno.com", UserRole.MENTOR, null);

		meeting = new Meeting();
		meeting.setOrganization(team);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(PLANNED);
		meeting.setDurationMinutes(45);
		meeting.setAgenda("Go-to-market");
		meeting.setStatus(MeetingStatus.SCHEDULED);
		entityManager.persist(meeting);
		entityManager.flush();
	}

	@Test
	void aNewTimeMovesTheMeetingOnlyOnceBothSidesSayYes() {
		MeetingProposalResponse proposed = proposals.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, NEW_TIME, null));

		assertThat(proposed.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(proposed.mentorAcceptedAt()).isNotNull(); // proposing is the mentor's yes
		assertThat(proposed.durationMinutes()).isEqualTo(45); // kept when not given
		assertThat(reloadedMeeting().getScheduledAt()).isEqualTo(PLANNED);

		assertThat(proposals.accept(mentor.getId(), proposed.id()).status()).isEqualTo(MeetingProposalStatus.PENDING);

		MeetingProposalResponse confirmed = proposals.accept(lead.getId(), proposed.id());

		assertThat(confirmed.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(confirmed.meetingId()).isEqualTo(meeting.getId()); // moved, not a second meeting
		Meeting moved = reloadedMeeting();
		assertThat(moved.getScheduledAt()).isEqualTo(NEW_TIME);
		assertThat(moved.getDurationMinutes()).isEqualTo(45);
		assertThat(moved.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(hrefsFor(member)).contains("/mentorships?tab=active"); // every member hears the meeting moved
		assertThatThrownBy(() -> proposals.accept(lead.getId(), proposed.id()))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void aTeamWithALeadAnswersThroughTheLeadOnly() {
		MeetingProposalResponse proposed = proposals.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, NEW_TIME, 60));

		assertThat(hrefsFor(lead)).isNotEmpty();
		assertThat(hrefsFor(member)).isEmpty();
		assertThatThrownBy(() -> proposals.accept(member.getId(), proposed.id()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(reloadedMeeting().getScheduledAt()).isEqualTo(PLANNED);

		assertThat(proposals.accept(lead.getId(), proposed.id()).status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(reloadedMeeting().getDurationMinutes()).isEqualTo(60);
	}

	@Test
	void anyMemberMayProposeAndTheMentorConfirms() {
		MeetingProposalResponse proposed = proposals.proposeReschedule(member.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, NEW_TIME, null));

		assertThat(proposed.teamAcceptedAt()).isNotNull();
		assertThat(proposed.teamResponderName()).isEqualTo(member.getFullName());
		assertThat(proposals.accept(mentor.getId(), proposed.id()).status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(reloadedMeeting().getScheduledAt()).isEqualTo(NEW_TIME);
	}

	@Test
	void outsidersPastTimesAndASecondOpenProposalAreRefused() {
		RescheduleMeetingRequest newTime = new RescheduleMeetingRequest(null, NEW_TIME, null);
		assertThatThrownBy(() -> proposals.proposeReschedule(outsider.getId(), meeting.getId(), newTime))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> proposals.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, Instant.now().minus(Duration.ofHours(1)), null)))
				.isInstanceOf(InvalidRequestException.class);

		MeetingProposalResponse proposed = proposals.proposeReschedule(mentor.getId(), meeting.getId(), newTime);

		assertThatThrownBy(() -> proposals.proposeReschedule(lead.getId(), meeting.getId(), newTime))
				.isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> proposals.accept(outsider.getId(), proposed.id()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(reloadedMeeting().getScheduledAt()).isEqualTo(PLANNED);
	}

	@Test
	void aDeclinedTimeLeavesTheMeetingAndTellsTheProposerWhy() {
		MeetingProposalResponse proposed = proposals.proposeReschedule(lead.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, NEW_TIME, null));

		MeetingProposalResponse declined = proposals.decline(mentor.getId(), proposed.id(),
				new DeclineMeetingProposalRequest(null, "Out of office that week"));

		assertThat(declined.status()).isEqualTo(MeetingProposalStatus.DECLINED);
		assertThat(declined.declineReason()).isEqualTo("Out of office that week");
		assertThat(reloadedMeeting().getScheduledAt()).isEqualTo(PLANNED);
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(lead.getId()))
				.extracting(Notification::getBody)
				.anyMatch(body -> body.contains("Out of office that week"));
		// Declined is final; the next new time is a new proposal, allowed again now.
		assertThat(proposals.proposeReschedule(mentor.getId(), meeting.getId(),
				new RescheduleMeetingRequest(null, NEW_TIME, null)).status()).isEqualTo(MeetingProposalStatus.PENDING);
	}

	private Meeting reloadedMeeting() {
		entityManager.flush();
		entityManager.clear();
		return entityManager.find(Meeting.class, meeting.getId());
	}

	private List<String> hrefsFor(AppUser user) {
		return notifications.findByRecipientIdOrderByCreatedAtDesc(user.getId()).stream()
				.map(Notification::getHref)
				.toList();
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
}
