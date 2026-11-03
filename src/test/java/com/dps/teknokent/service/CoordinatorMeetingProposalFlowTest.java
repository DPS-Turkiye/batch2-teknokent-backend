package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingProposal;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.NotificationRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class CoordinatorMeetingProposalFlowTest {

	@Autowired
	private MeetingProposalService proposals;

	@Autowired
	private MeetingRepository meetings;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private EntityManager entityManager;

	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser lead;
	private AppUser member;
	private AppUser mentor;
	private Organization team;
	private Program program;
	private Mentorship mentorship;
	private Instant proposedTime;

	@BeforeEach
	void aCoordinatorManagesAProgramWithAnActiveMentorship() {
		proposedTime = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MINUTES);
		team = new Organization();
		team.setName("Proposal Flow Team");
		entityManager.persist(team);
		lead = persistUser("proposal-lead@flow.test", UserRole.MENTEE, team);
		member = persistUser("proposal-member@flow.test", UserRole.MENTEE, team);
		team.setFounder(lead);
		mentor = persistUser("proposal-mentor@flow.test", UserRole.MENTOR, null);
		coordinator = persistUser("proposal-coordinator@flow.test", UserRole.COORDINATOR, null);
		otherCoordinator = persistUser("other-coordinator@flow.test", UserRole.COORDINATOR, null);

		program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Proposal Flow Program");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		entityManager.persist(program);
		ProgramStartup membership = new ProgramStartup();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(program.getId());
		membership.setOrganizationId(team.getId());
		membership.setCreatedAt(Instant.now());
		entityManager.persist(membership);

		mentorship = new Mentorship();
		mentorship.setOrganization(team);
		mentorship.setMentor(mentor);
		mentorship.setStatus(MentorshipStatus.ACTIVE);
		mentorship.setStartDate(LocalDate.now().minusDays(1));
		entityManager.persist(mentorship);
		entityManager.flush();
	}

	@ParameterizedTest(name = "mentor answers first: {0}")
	@ValueSource(booleans = {true, false})
	void bothSidesMustApproveBeforeExactlyOneMeetingIsCreated(boolean mentorFirst) {
		MeetingProposalResponse proposed = propose();
		assertThat(proposed.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(proposed.mentorAcceptedAt()).isNull();
		assertThat(proposed.teamAcceptedAt()).isNull();
		assertThat(proposals.mine(mentor.getId())).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo(proposed.id());
			assertThat(item.canRespond()).isTrue();
		});
		assertThat(proposals.mine(lead.getId())).singleElement()
				.satisfies(item -> assertThat(item.canRespond()).isTrue());
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(member.getId())).isEmpty();
		assertThat(teamMeetings().size()).isZero();

		UUID first = mentorFirst ? mentor.getId() : lead.getId();
		UUID second = mentorFirst ? lead.getId() : mentor.getId();
		MeetingProposalResponse halfAccepted = proposals.accept(first, proposed.id());
		assertThat(halfAccepted.status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(halfAccepted.meetingId()).isNull();
		assertThat(proposals.accept(first, proposed.id()).status()).isEqualTo(MeetingProposalStatus.PENDING);
		flushAndClear();
		assertThat(teamMeetings().size()).isZero();

		MeetingProposalResponse confirmed = proposals.accept(second, proposed.id());
		flushAndClear();
		assertThat(confirmed.status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
		assertThat(confirmed.mentorAcceptedAt()).isNotNull();
		assertThat(confirmed.teamAcceptedAt()).isNotNull();
		assertThat(confirmed.teamResponderName()).isEqualTo(lead.getFullName());
		assertThat(teamMeetings()).singleElement().satisfies(meeting -> {
			assertThat(meeting.getId()).isEqualTo(confirmed.meetingId());
			assertThat(meeting.getOrganization().getId()).isEqualTo(team.getId());
			assertThat(meeting.getMentor().getId()).isEqualTo(mentor.getId());
			assertThat(meeting.getScheduledAt()).isEqualTo(proposedTime);
			assertThat(meeting.getDurationMinutes()).isEqualTo(60);
			assertThat(meeting.getAgenda()).isEqualTo("Review the pilot results");
			assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
		});
		assertThat(entityManager.find(MeetingProposal.class, proposed.id()).getMeetingId())
				.isEqualTo(confirmed.meetingId());
		assertThat(proposals.mine(mentor.getId())).isEmpty();
		assertThat(proposals.mine(lead.getId())).isEmpty();
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(member.getId()))
				.extracting(Notification::getHref).contains("/mentorships?tab=active");
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(coordinator.getId()))
				.extracting(Notification::getHref)
				.contains("/coordinator/mentorships?mentorship=" + mentorship.getId());
		assertThatThrownBy(() -> proposals.accept(second, proposed.id())).isInstanceOf(ConflictException.class);
		assertThat(teamMeetings().size()).isEqualTo(1);
	}

	@Test
	void anUnrelatedCoordinatorCannotProposeAndAMemberCannotAnswerForTheLead() {
		assertThatThrownBy(() -> proposals.propose(otherCoordinator.getId(), mentorship.getId(), proposalRequest()))
				.isInstanceOf(InvalidRequestException.class);
		MeetingProposalResponse proposed = propose();
		assertThat(proposals.mine(member.getId())).singleElement()
				.satisfies(item -> assertThat(item.canRespond()).isFalse());
		assertThatThrownBy(() -> proposals.accept(member.getId(), proposed.id()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> proposals.accept(otherCoordinator.getId(), proposed.id()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(proposals.accept(mentor.getId(), proposed.id()).status()).isEqualTo(MeetingProposalStatus.PENDING);
		assertThat(teamMeetings().size()).isZero();
		assertThat(proposals.accept(lead.getId(), proposed.id()).status()).isEqualTo(MeetingProposalStatus.CONFIRMED);
	}

	@ParameterizedTest(name = "mentor declines: {0}")
	@ValueSource(booleans = {true, false})
	void eitherSideCanDeclineAfterTheOtherAgreesWithoutCreatingAMeeting(boolean mentorDeclines) {
		MeetingProposalResponse proposed = propose();
		proposals.accept(mentorDeclines ? lead.getId() : mentor.getId(), proposed.id());
		MeetingProposalResponse declined = proposals.decline(mentorDeclines ? mentor.getId() : lead.getId(), proposed.id(),
				new DeclineMeetingProposalRequest(null, "Unavailable that week"));
		flushAndClear();
		assertThat(declined.status()).isEqualTo(MeetingProposalStatus.DECLINED);
		assertThat(entityManager.find(MeetingProposal.class, proposed.id()).getDeclineReason())
				.isEqualTo("Unavailable that week");
		assertThat(teamMeetings().size()).isZero();
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(coordinator.getId()))
				.extracting(Notification::getBody).anyMatch(body -> body.contains("Unavailable that week"));
		assertThatThrownBy(() -> proposals.accept(lead.getId(), proposed.id())).isInstanceOf(ConflictException.class);
		assertThat(propose().status()).isEqualTo(MeetingProposalStatus.PENDING);
	}

	@Test
	void onlyOneProposalCanBePendingAndWithdrawalPreventsLaterConfirmation() {
		MeetingProposalResponse proposed = propose();
		assertThatThrownBy(this::propose).isInstanceOf(ConflictException.class);
		proposals.accept(mentor.getId(), proposed.id());
		proposals.withdraw(coordinator.getId(), proposed.id());
		flushAndClear();
		assertThat(entityManager.find(MeetingProposal.class, proposed.id()).getStatus())
				.isEqualTo(MeetingProposalStatus.WITHDRAWN);
		assertThatThrownBy(() -> proposals.accept(lead.getId(), proposed.id())).isInstanceOf(ConflictException.class);
		assertThat(teamMeetings().size()).isZero();
		assertThat(propose().status()).isEqualTo(MeetingProposalStatus.PENDING);
	}

	private MeetingProposalResponse propose() {
		return proposals.propose(coordinator.getId(), mentorship.getId(), proposalRequest());
	}

	private ProposeMeetingRequest proposalRequest() {
		return new ProposeMeetingRequest(null, proposedTime, 60, "Review the pilot results", null);
	}

	private List<Meeting> teamMeetings() {
		return meetings.findByOrganizationIdOrderByScheduledAtDesc(team.getId());
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private AppUser persistUser(String email, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(organization);
		entityManager.persist(user);
		return user;
	}
}
