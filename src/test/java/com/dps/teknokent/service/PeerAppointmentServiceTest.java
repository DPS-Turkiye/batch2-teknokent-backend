package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AppointmentKind;
import com.dps.teknokent.domain.AppointmentStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AppointmentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;

class PeerAppointmentServiceTest {

	private final PeerMentoringService peerMentoring = mock(PeerMentoringService.class);
	private final MentorshipRequestRepository requests = mock(MentorshipRequestRepository.class);
	private final MeetingRepository meetings = mock(MeetingRepository.class);
	private PeerAppointmentService service;

	private AppUser ayse;
	private AppUser zeynep;
	private AppUser mehmet;

	@BeforeEach
	void setUp() {
		service = new PeerAppointmentService(peerMentoring, requests, meetings);
		ayse = user("Ayşe Yılmaz");
		zeynep = user("Zeynep Demir");
		mehmet = user("Mehmet Kaya");
		when(peerMentoring.requireStudent(zeynep.getId())).thenReturn(zeynep);
		when(peerMentoring.departmentOf(ayse.getId(), null)).thenReturn("Bilgisayar Mühendisliği");
		when(peerMentoring.departmentOf(mehmet.getId(), null)).thenReturn("Elektrik-Elektronik Mühendisliği");
	}

	@Test
	void listsBookedPeerSessionsOnBothSidesSoonestFirst() {
		Instant later = Instant.parse("2026-11-20T09:00:00Z");
		Instant sooner = Instant.parse("2026-11-12T11:00:00Z");
		MentorshipRequest asMentee = request(zeynep, mehmet, "CAREER_INTERNSHIP", "CV'me bakar mısın?");
		MentorshipRequest asMentor = request(ayse, zeynep, "INTERNATIONAL", "Erasmus başvurusu");
		MentorshipRequest teknokent = request(zeynep, mehmet, "Fintech", "Girişim");
		MentorshipRequest unbooked = request(ayse, zeynep, "CAMPUS_LIFE", "Kulüpler");
		when(requests.findByRequesterIdOrderByCreatedAtDesc(zeynep.getId())).thenReturn(List.of(asMentee, teknokent));
		when(requests.findByMentorIdOrderByCreatedAtDesc(zeynep.getId())).thenReturn(List.of(asMentor, unbooked));
		when(meetings.findByMentorshipRequestIdIn(any())).thenAnswer(invocation -> {
			Collection<UUID> ids = invocation.getArgument(0);
			assertThat(ids).containsExactlyInAnyOrder(asMentee.getId(), asMentor.getId(), unbooked.getId());
			return List.of(meeting(asMentee, later, 45, MeetingStatus.SCHEDULED),
					meeting(asMentor, sooner, 30, MeetingStatus.CANCELLED));
		});

		List<AppointmentResponse> result = service.appointments(zeynep.getId());

		assertThat(result).extracting(AppointmentResponse::requestId).containsExactly(asMentor.getId(), asMentee.getId());
		AppointmentResponse mentoring = result.get(0);
		assertThat(mentoring.kind()).isEqualTo(AppointmentKind.PEER_MENTORING);
		assertThat(mentoring.side()).isEqualTo(UserRole.MENTOR);
		assertThat(mentoring.status()).isEqualTo(AppointmentStatus.CANCELLED);
		assertThat(mentoring.category()).isEqualTo(PeerTopicCategory.INTERNATIONAL);
		assertThat(mentoring.counterpart().fullName()).isEqualTo("Ayşe Yılmaz");
		assertThat(mentoring.counterpart().department()).isEqualTo("Bilgisayar Mühendisliği");
		assertThat(mentoring.endsAt()).isEqualTo(sooner.plusSeconds(30 * 60));
		AppointmentResponse mentee = result.get(1);
		assertThat(mentee.side()).isEqualTo(UserRole.MENTEE);
		assertThat(mentee.status()).isEqualTo(AppointmentStatus.CONFIRMED);
		assertThat(mentee.topic()).isEqualTo("CV'me bakar mısın?");
		assertThat(mentee.counterpart().userId()).isEqualTo(mehmet.getId());
		assertThat(mentee.onlineUrl()).isEqualTo("https://meet.google.com/abc-defg-hij");
	}

	@Test
	void aStudentWithoutPeerRequestsHasNoAppointmentsAndNoMeetingLookup() {
		when(requests.findByRequesterIdOrderByCreatedAtDesc(zeynep.getId())).thenReturn(List.of());
		when(requests.findByMentorIdOrderByCreatedAtDesc(zeynep.getId())).thenReturn(List.of());

		assertThat(service.appointments(zeynep.getId())).isEmpty();
		verify(meetings, never()).findByMentorshipRequestIdIn(any());
	}

	@Test
	void onlyAnOdtuStudentGetsIn() {
		when(peerMentoring.requireStudent(mehmet.getId())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> service.appointments(mehmet.getId())).isInstanceOf(ResponseStatusException.class);
		verify(requests, never()).findByRequesterIdOrderByCreatedAtDesc(any());
	}

	private static AppUser user(String name) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(name);
		return user;
	}

	private static MentorshipRequest request(AppUser requester, AppUser mentor, String supportArea, String summary) {
		MentorshipRequest request = new MentorshipRequest();
		request.setId(UUID.randomUUID());
		request.setRequester(requester);
		request.setMentor(mentor);
		request.setSupportArea(supportArea);
		request.setSummary(summary);
		request.setStatus(MentorshipRequestStatus.ACCEPTED);
		return request;
	}

	private static Meeting meeting(MentorshipRequest request, Instant at, int minutes, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setId(UUID.randomUUID());
		meeting.setMentorshipRequest(request);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(minutes);
		meeting.setStatus(status);
		meeting.setMeetLink("https://meet.google.com/abc-defg-hij");
		return meeting;
	}
}
