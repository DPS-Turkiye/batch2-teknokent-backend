package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.dto.requests.ScheduleMentorshipRequest;
import com.dps.teknokent.dto.responses.MeetingResponse;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProposedSlots;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class MentorshipRequestBookingFlowTest {

	@Autowired
	private MentorshipRequestService requests;

	@Autowired
	private MeetingRepository meetings;

	@Autowired
	private DocumentRequestItemRepository documents;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private EntityManager entityManager;

	private Organization team;
	private AppUser requester;
	private AppUser otherMember;
	private AppUser mentor;
	private AppUser otherMentor;
	private MentorAvailabilitySlot slot;

	@BeforeEach
	void anApprovedTeamCanRequestAMentorWithAnAvailableSlot() {
		team = new Organization();
		team.setName("Booking Flow Team");
		entityManager.persist(team);
		requester = persistUser("booking-founder@flow.test", UserRole.MENTEE, team);
		otherMember = persistUser("booking-member@flow.test", UserRole.MENTEE, team);
		team.setFounder(requester);
		mentor = persistUser("booking-mentor@flow.test", UserRole.MENTOR, null);
		otherMentor = persistUser("other-booking-mentor@flow.test", UserRole.MENTOR, null);
		slot = persistSlot(mentor, Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MINUTES));
		entityManager.flush();
	}

	@Test
	void acceptingPreferredTimesBooksTheFirstFreeTimeAndLinksItToTheRequest() {
		Instant busyTime = slot.getStartsAt().minus(Duration.ofHours(2));
		persistMeetingAt(busyTime);
		MentorshipRequestResponse submitted = createRequest(
				ProposedSlots.PREFERRED_LINE_PREFIX + machineSlot(busyTime) + "," + machineSlot(slot.getStartsAt()));
		assertThat(submitted.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(meetings.findByMentorshipRequestId(submitted.id())).isEmpty();
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(mentor.getId()))
				.extracting(Notification::getHref).contains("/mentor-dashboard");

		assertThat(decide(submitted.id(), MentorshipRequestStatus.ACCEPTED, "Happy to help").status())
				.isEqualTo(MentorshipRequestStatus.ACCEPTED);
		flushAndClear();

		Meeting booked = meetingFor(submitted.id());
		assertBooking(booked, submitted.id(), slot.getStartsAt());
		assertThat(teamMeetings().size()).isEqualTo(2);
		assertThat(requests.findById(submitted.id()).expectedOutcome()).isEqualTo("A launch plan");
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(requester.getId()))
				.extracting(Notification::getHref).contains("/mentor-request/status?request=" + submitted.id());
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, slot)))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(meetingFor(submitted.id()).getId()).isEqualTo(booked.getId());
	}

	@Test
	void acceptanceWithoutATimeLetsTheRequesterBookOnceAndRemovesThatSlotFromOtherRequests() {
		MentorshipRequestResponse submitted = createRequest(null);
		decide(submitted.id(), MentorshipRequestStatus.ACCEPTED, "Choose an available time");
		assertThat(teamMeetings().size()).isZero();
		assertThat(requests.findAvailability(submitted.id())).singleElement()
				.satisfies(available -> assertThat(available.id()).isEqualTo(slot.getId()));

		MeetingResponse booked = requests.scheduleMeeting(submitted.id(), booking(requester, slot));
		flushAndClear();
		assertThat(booked.mentorshipRequestId()).isEqualTo(submitted.id());
		assertBooking(meetingFor(submitted.id()), submitted.id(), slot.getStartsAt());
		assertThat(requests.findAvailability(submitted.id())).isEmpty();
		assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(mentor.getId()))
				.extracting(Notification::getHref).contains("/mentor-dashboard/calendar");
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, slot)))
				.isInstanceOf(InvalidRequestException.class);

		MentorshipRequestResponse competing = createRequest(null);
		decide(competing.id(), MentorshipRequestStatus.ACCEPTED, "Choose a time");
		assertThat(requests.findAvailability(competing.id())).isEmpty();
		assertThatThrownBy(() -> requests.scheduleMeeting(competing.id(), booking(requester, slot)))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(teamMeetings().size()).isEqualTo(1);
		assertThat(meetings.findByMentorshipRequestId(competing.id())).isEmpty();
	}

	@Test
	void bookingRefusesAnUnacceptedRequestOtherPeopleOtherMentorsAndShortNoticeSlots() {
		MentorshipRequestResponse submitted = createRequest(null);
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, slot)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> requests.decide(submitted.id(), new DecideMentorshipRequest(
				otherMentor.getId(), MentorshipRequestStatus.ACCEPTED, "I accept", List.of())))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(requests.findById(submitted.id()).status()).isEqualTo(MentorshipRequestStatus.PENDING);
		decide(submitted.id(), MentorshipRequestStatus.ACCEPTED, "Choose a time");

		MentorAvailabilitySlot wrongMentor = persistSlot(otherMentor, slot.getStartsAt());
		MentorAvailabilitySlot tooSoon = persistSlot(mentor,
				Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES));
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(otherMember, slot)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, wrongMentor)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, tooSoon)))
				.isInstanceOf(InvalidRequestException.class);
		flushAndClear();
		assertThat(teamMeetings().size()).isZero();
		requests.scheduleMeeting(submitted.id(), booking(requester, slot));
		assertBooking(meetingFor(submitted.id()), submitted.id(), slot.getStartsAt());
	}

	@Test
	void aCounterProposalBooksOnlyTheOfferedTimeAcceptedByTheOriginalRequester() {
		MentorshipRequestResponse submitted = createRequest(null);
		String offered = machineSlot(slot.getStartsAt());
		assertThat(decide(submitted.id(), MentorshipRequestStatus.COUNTER_PROPOSED,
				"Would this work?\n" + ProposedSlots.LINE_PREFIX + offered).status())
				.isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
		assertThat(teamMeetings().size()).isZero();
		assertThatThrownBy(() -> requests.acceptProposedSlot(submitted.id(),
				new AcceptProposedSlotRequest(otherMember.getId(), offered)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> requests.acceptProposedSlot(submitted.id(), new AcceptProposedSlotRequest(
				requester.getId(), machineSlot(slot.getStartsAt().plus(Duration.ofDays(1))))))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(teamMeetings().size()).isZero();

		MentorshipRequestResponse accepted = requests.acceptProposedSlot(submitted.id(),
				new AcceptProposedSlotRequest(requester.getId(), offered));
		flushAndClear();
		assertThat(accepted.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertBooking(meetingFor(submitted.id()), submitted.id(), slot.getStartsAt());
		assertThatThrownBy(() -> requests.acceptProposedSlot(submitted.id(),
				new AcceptProposedSlotRequest(requester.getId(), offered)))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(teamMeetings().size()).isEqualTo(1);
	}

	@Test
	void informationAndRequestedDocumentsSurviveTheReplyAndAutomaticBooking() {
		MentorshipRequestResponse submitted = createRequest(
				ProposedSlots.PREFERRED_LINE_PREFIX + machineSlot(slot.getStartsAt()));
		MentorshipRequestResponse asking = requests.decide(submitted.id(), new DecideMentorshipRequest(
				mentor.getId(), MentorshipRequestStatus.MORE_INFO_REQUESTED, "What did the pilot show?",
				List.of("Pilot summary")));
		assertThat(asking.status()).isEqualTo(MentorshipRequestStatus.MORE_INFO_REQUESTED);
		assertThat(teamMeetings().size()).isZero();
		MentorshipRequestResponse replied = requests.reply(submitted.id(),
				new ReplyToMentorshipRequest(requester.getId(), "Ten teams completed the pilot"));
		assertThat(replied.status()).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(replied.messages()).hasSize(2);
		decide(submitted.id(), MentorshipRequestStatus.ACCEPTED, "Let's discuss the results");
		flushAndClear();

		Meeting booked = meetingFor(submitted.id());
		assertBooking(booked, submitted.id(), slot.getStartsAt());
		assertThat(requests.findById(submitted.id()).messages()).hasSize(2);
		assertThat(documents.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(submitted.id()))
				.extracting(DocumentRequestItem::getTitle).containsExactly("Pilot summary");
		assertThat(documents.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(booked.getId()))
				.singleElement().satisfies(document -> {
					assertThat(document.getTitle()).isEqualTo("Pilot summary");
					assertThat(document.getOrganization().getId()).isEqualTo(team.getId());
					assertThat(document.getCreatedBy().getId()).isEqualTo(mentor.getId());
				});
	}

	@Test
	void decliningARequestWithPreferredTimesNeverCreatesAMeetingOrAllowsBooking() {
		MentorshipRequestResponse submitted = createRequest(
				ProposedSlots.PREFERRED_LINE_PREFIX + machineSlot(slot.getStartsAt()));
		decide(submitted.id(), MentorshipRequestStatus.DECLINED, "Outside my expertise");
		flushAndClear();
		assertThat(requests.findById(submitted.id()).status()).isEqualTo(MentorshipRequestStatus.DECLINED);
		assertThatThrownBy(() -> requests.scheduleMeeting(submitted.id(), booking(requester, slot)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> decide(submitted.id(), MentorshipRequestStatus.ACCEPTED, "Changed my mind"))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(teamMeetings().size()).isZero();
	}

	private MentorshipRequestResponse createRequest(String message) {
		return requests.create(new CreateMentorshipRequestSubmission(requester.getId(), team.getId(), mentor.getId(),
				team.getName(), "Go-to-market", "Help us launch our product", MentorshipRequestUrgency.NEXT_TWO_WEEKS,
				MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE, message, null, "A launch plan"));
	}

	private MentorshipRequestResponse decide(UUID requestId, MentorshipRequestStatus status, String message) {
		return requests.decide(requestId, new DecideMentorshipRequest(mentor.getId(), status, message, List.of()));
	}

	private static ScheduleMentorshipRequest booking(AppUser user, MentorAvailabilitySlot selected) {
		return new ScheduleMentorshipRequest(user.getId(), selected.getId());
	}

	private static String machineSlot(Instant time) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(time.atZone(MeetingSlotTimes.APP_ZONE));
	}

	private Meeting meetingFor(UUID requestId) {
		var booked = meetings.findByMentorshipRequestId(requestId);
		assertThat(booked).as("meeting linked to request %s", requestId).isPresent();
		return booked.orElseThrow();
	}

	private void assertBooking(Meeting meeting, UUID requestId, Instant time) {
		assertThat(meeting.getMentorshipRequest().getId()).isEqualTo(requestId);
		assertThat(meeting.getOrganization().getId()).isEqualTo(team.getId());
		assertThat(meeting.getMentor().getId()).isEqualTo(mentor.getId());
		assertThat(meeting.getScheduledAt()).isEqualTo(time);
		assertThat(meeting.getDurationMinutes()).isEqualTo(45);
		assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
	}

	private List<Meeting> teamMeetings() {
		return meetings.findByOrganizationIdOrderByScheduledAtDesc(team.getId());
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private MentorAvailabilitySlot persistSlot(AppUser user, Instant time) {
		MentorAvailabilitySlot available = new MentorAvailabilitySlot();
		available.setMentor(user);
		available.setStartsAt(time);
		available.setDurationMinutes(45);
		entityManager.persist(available);
		return available;
	}

	private void persistMeetingAt(Instant time) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(team);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(time);
		meeting.setDurationMinutes(45);
		meeting.setAgenda("An existing booking");
		meeting.setStatus(MeetingStatus.SCHEDULED);
		entityManager.persist(meeting);
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
