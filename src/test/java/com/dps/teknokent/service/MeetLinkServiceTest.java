package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class MeetLinkServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-25T07:00:00Z");
	private static final Instant TOMORROW = NOW.plus(Duration.ofDays(1));
	private static final String LINK = "https://meet.google.com/abc-mnop-xyz";

	@Autowired
	private MeetingRepository meetingRepository;
	@Autowired
	private MentorshipRequestRepository requestRepository;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private EntityManager entityManager;

	private final GoogleMeetClient google = mock(GoogleMeetClient.class);
	private AppUser mentor;
	private Organization team;
	private Organization otherTeam;

	@BeforeEach
	void setUp() {
		when(google.createMeeting()).thenReturn(LINK);
		mentor = user("Leyla Aksoy", UserRole.MENTOR);
		team = organization("Döngü");
		otherTeam = organization("Vega");
	}

	@Test
	void givesAnUpcomingMeetingWithoutALinkOne() {
		Meeting meeting = meeting(team, TOMORROW, MeetingStatus.SCHEDULED, null, null);
		Meeting blank = meeting(team, TOMORROW.plusSeconds(7200), MeetingStatus.SCHEDULED, "", null);

		service(NOW).createMissingLinks();

		assertThat(linkOf(meeting)).isEqualTo(LINK);
		assertThat(linkOf(blank)).isEqualTo(LINK);
		verify(google, times(2)).createMeeting();
	}

	@Test
	void leavesPastCancelledInPersonJustMadeAndLinkedMeetingsAlone() {
		meeting(team, NOW.minusSeconds(60), MeetingStatus.SCHEDULED, null, null);
		meeting(team, TOMORROW, MeetingStatus.CANCELLED, null, null);
		meeting(team, TOMORROW.plusSeconds(3600), MeetingStatus.SCHEDULED, null, request(MeetingMode.IN_PERSON));
		Meeting own = meeting(team, TOMORROW.plusSeconds(7200), MeetingStatus.SCHEDULED,
				"https://meet.google.com/own-link-abc", null);
		Meeting fresh = meeting(team, TOMORROW.plusSeconds(9000), MeetingStatus.SCHEDULED, null, null);
		made(fresh, NOW.minusSeconds(20));

		service(NOW).createMissingLinks();

		verify(google, never()).createMeeting();
		assertThat(linkOf(own)).isEqualTo("https://meet.google.com/own-link-abc");
	}

	@Test
	void anOnlineOrUndecidedRequestGetsALink() {
		Meeting online = meeting(team, TOMORROW, MeetingStatus.SCHEDULED, null, request(MeetingMode.ONLINE));
		Meeting undecided = meeting(team, TOMORROW.plusSeconds(3600), MeetingStatus.SCHEDULED, null,
				request(MeetingMode.NO_PREFERENCE));

		service(NOW).createMissingLinks();

		assertThat(linkOf(online)).isEqualTo(LINK);
		assertThat(linkOf(undecided)).isEqualTo(LINK);
	}

	@Test
	void teamsInOneGroupSessionShareOneLink() {
		Meeting first = meeting(team, TOMORROW, MeetingStatus.SCHEDULED, null, null);
		Meeting second = meeting(otherTeam, TOMORROW, MeetingStatus.SCHEDULED, null, null);

		service(NOW).createMissingLinks();

		assertThat(linkOf(first)).isEqualTo(LINK);
		assertThat(linkOf(second)).isEqualTo(LINK);
		verify(google, times(1)).createMeeting();
	}

	@Test
	void aSeatAddedToASessionThatHasALinkGetsThatLink() {
		meeting(team, TOMORROW, MeetingStatus.SCHEDULED, "https://meet.google.com/own-link-abc", null);
		Meeting added = meeting(otherTeam, TOMORROW, MeetingStatus.SCHEDULED, null, null);

		service(NOW).createMissingLinks();

		assertThat(linkOf(added)).isEqualTo("https://meet.google.com/own-link-abc");
		verify(google, never()).createMeeting();
	}

	@Test
	void aLinkEnteredMeanwhileIsNotReplaced() {
		Meeting meeting = meeting(team, TOMORROW, MeetingStatus.SCHEDULED, null, null);
		when(google.createMeeting()).thenAnswer(call -> {
			entityManager.createNativeQuery("update meeting set meet_link = 'https://zoom.us/j/1' where id = ?1")
					.setParameter(1, meeting.getId())
					.executeUpdate();
			return LINK;
		});

		service(NOW).createMissingLinks();

		assertThat(linkOf(meeting)).isEqualTo("https://zoom.us/j/1");
	}

	@Test
	void afterARefusalGoogleIsLeftAloneForAWhileAndThenAskedAgain() {
		Meeting meeting = meeting(team, TOMORROW, MeetingStatus.SCHEDULED, null, null);
		Clock clock = mock(Clock.class);
		when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60), NOW.plus(Duration.ofMinutes(10)));
		when(google.createMeeting()).thenThrow(new IllegalStateException("refused")).thenReturn(LINK);
		MeetLinkService service = new MeetLinkService(meetingRepository, google, clock);

		service.createMissingLinks();
		service.createMissingLinks();
		verify(google, times(1)).createMeeting();
		service.createMissingLinks();

		assertThat(linkOf(meeting)).isEqualTo(LINK);
	}

	@Test
	void theSchedulerExistsOnlyWhereItIsTurnedOn() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(MeetLinkService.class, () -> mock(MeetLinkService.class))
				.withUserConfiguration(MeetLinkScheduler.class);

		runner.run(context -> assertThat(context).doesNotHaveBean(MeetLinkScheduler.class));
		runner.withPropertyValues("app.google-meet.enabled=true").run(context -> {
			context.getBean(MeetLinkScheduler.class).createMissingLinks();
			verify(context.getBean(MeetLinkService.class)).createMissingLinks();
		});
	}

	private MeetLinkService service(Instant now) {
		return new MeetLinkService(meetingRepository, google, Clock.fixed(now, ZoneOffset.UTC));
	}

	private String linkOf(Meeting meeting) {
		entityManager.clear();
		return meetingRepository.findById(meeting.getId()).orElseThrow().getMeetLink();
	}

	private Meeting meeting(Organization of, Instant at, MeetingStatus status, String link,
			MentorshipRequest request) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(of);
		meeting.setMentor(mentor);
		meeting.setMentorshipRequest(request);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(45);
		meeting.setStatus(status);
		meeting.setMeetLink(link);
		Meeting saved = meetingRepository.saveAndFlush(meeting);
		made(saved, NOW.minus(Duration.ofHours(1)));
		return saved;
	}

	private void made(Meeting meeting, Instant at) {
		entityManager.createNativeQuery("update meeting set created_at = ?1 where id = ?2")
				.setParameter(1, Timestamp.from(at))
				.setParameter(2, meeting.getId())
				.executeUpdate();
	}

	private MentorshipRequest request(MeetingMode mode) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(user("Deniz Ak", UserRole.MENTEE));
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setRequestedForName("Döngü");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(mode);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		return requestRepository.saveAndFlush(request);
	}

	private AppUser user(String name, UserRole role) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@meetlink.test");
		user.setRole(role);
		return userRepository.saveAndFlush(user);
	}

	private Organization organization(String name) {
		Organization organization = new Organization();
		organization.setName(name);
		return organizationRepository.saveAndFlush(organization);
	}
}
