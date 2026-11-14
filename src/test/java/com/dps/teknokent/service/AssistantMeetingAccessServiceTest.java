package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class AssistantMeetingAccessServiceTest {

	private static final String CODE = "abc-defg-hij";
	private static final String ROOM = "https://meet.google.com/" + CODE;
	private static final Instant NOW = Instant.parse("2026-09-15T16:10:00Z");

	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private AppUserRepository appUserRepository;

	private final UUID mentorId = UUID.randomUUID();
	private final Organization teamNow = organization();
	private final Organization teamLastWeek = organization();
	private final Organization guestTeam = organization();

	private AssistantMeetingAccessService service() {
		return new AssistantMeetingAccessService(meetingRepository, appUserRepository,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void letsTheTeamWhoseSessionIsOnNowUseTheAssistant() {
		inTheRoom(meeting(teamNow, NOW.minus(10, ChronoUnit.MINUTES), ROOM),
				meeting(teamLastWeek, NOW.minus(7, ChronoUnit.DAYS), ROOM));
		AppUser member = memberOf(teamNow);

		assertThat(service().requireAccess(member.getId(), "google_meet", CODE).nativeMeetingId()).isEqualTo(CODE);
	}

	@Test
	void refusesATeamFromAnEarlierSessionInTheMentorsPersonalRoom() {
		inTheRoom(meeting(teamNow, NOW.minus(10, ChronoUnit.MINUTES), ROOM),
				meeting(teamLastWeek, NOW.minus(7, ChronoUnit.DAYS), ROOM));
		AppUser earlier = memberOf(teamLastWeek);

		assertThatThrownBy(() -> service().requireAccess(earlier.getId(), "google_meet", CODE))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void refusesATeamWhoseSessionHasNotStartedYet() {
		inTheRoom(meeting(teamNow, NOW.minus(10, ChronoUnit.MINUTES), ROOM),
				meeting(teamLastWeek, NOW.plus(1, ChronoUnit.HOURS), ROOM));
		AppUser later = memberOf(teamLastWeek);

		assertThatThrownBy(() -> service().requireAccess(later.getId(), "google_meet", CODE))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void letsTheNextTeamInWithinTheBotsJoinLead() {
		inTheRoom(meeting(teamLastWeek, NOW.minus(1, ChronoUnit.HOURS), ROOM),
				meeting(teamNow, NOW.plus(3, ChronoUnit.MINUTES), ROOM));
		AppUser next = memberOf(teamNow);

		assertThat(service().requireAccess(next.getId(), "google_meet", CODE)).isNotNull();
	}

	@Test
	void letsAGuestTeamOnAnotherSpellingOfTheLinkInWithTheSameSession() {
		Instant start = NOW.minus(5, ChronoUnit.MINUTES);
		inTheRoom(meeting(teamNow, start, ROOM), meeting(guestTeam, start, ROOM + "?authuser=1"));
		AppUser guest = memberOf(guestTeam);

		assertThat(service().requireAccess(guest.getId(), "google_meet", CODE)).isNotNull();
	}

	@Test
	void letsTheFirstSessionsTeamTryTheAssistantBeforeItStarts() {
		inTheRoom(meeting(teamNow, NOW.plus(1, ChronoUnit.DAYS), ROOM),
				meeting(teamLastWeek, NOW.plus(8, ChronoUnit.DAYS), ROOM));

		assertThat(service().requireAccess(memberOf(teamNow).getId(), "google_meet", CODE)).isNotNull();
		assertThatThrownBy(() -> service().requireAccess(memberOf(teamLastWeek).getId(), "google_meet", CODE))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void aCancelledMeetingIsNotTheSession() {
		Meeting cancelled = meeting(teamNow, NOW.minus(5, ChronoUnit.MINUTES), ROOM);
		cancelled.setStatus(MeetingStatus.CANCELLED);
		inTheRoom(cancelled, meeting(teamLastWeek, NOW.minus(1, ChronoUnit.DAYS), ROOM));

		assertThatThrownBy(() -> service().requireAccess(memberOf(teamNow).getId(), "google_meet", CODE))
				.isInstanceOf(ResponseStatusException.class);
		assertThat(service().requireAccess(memberOf(teamLastWeek).getId(), "google_meet", CODE)).isNotNull();
	}

	@Test
	void letsTheMentorAndAnAdminIn() {
		inTheRoom(meeting(teamNow, NOW.minus(10, ChronoUnit.MINUTES), ROOM));
		AppUser mentor = user(mentorId, UserRole.MENTOR, null);
		AppUser admin = user(UUID.randomUUID(), UserRole.ADMIN, null);
		when(appUserRepository.findById(mentorId)).thenReturn(Optional.of(mentor));
		when(appUserRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

		assertThat(service().requireAccess(mentorId, "google_meet", CODE)).isNotNull();
		assertThat(service().requireAccess(admin.getId(), "google_meet", CODE)).isNotNull();
	}

	@Test
	void answersNotFoundForACodeWithoutAMeetingAndRefusesAnUnnamedCaller() {
		when(meetingRepository.findByMeetLinkContainingIgnoreCase(CODE)).thenReturn(List.of());

		assertThatThrownBy(() -> service().requireAccess(UUID.randomUUID(), "google_meet", CODE))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service().requireAccess(null, "google_meet", CODE))
				.isInstanceOf(ResponseStatusException.class);
	}

	private void inTheRoom(Meeting... meetings) {
		when(meetingRepository.findByMeetLinkContainingIgnoreCase(CODE)).thenReturn(List.of(meetings));
	}

	private Meeting meeting(Organization team, Instant start, String link) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setOrganization(team);
		meeting.setMentor(user(mentorId, UserRole.MENTOR, null));
		meeting.setScheduledAt(start);
		meeting.setDurationMinutes(60);
		meeting.setMeetLink(link);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	private AppUser memberOf(Organization team) {
		AppUser member = user(UUID.randomUUID(), UserRole.MENTEE, team);
		when(appUserRepository.findById(member.getId())).thenReturn(Optional.of(member));
		return member;
	}

	private static AppUser user(UUID id, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setOrganization(organization);
		return user;
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		return organization;
	}
}
