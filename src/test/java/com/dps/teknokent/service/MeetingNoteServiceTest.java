package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingRoomEventType;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.requests.CreateMeetingTodoRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingNote;
import com.dps.teknokent.entity.MeetingTodo;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingTodoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingNoteServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MeetingNoteRepository meetingNoteRepository;
	@Mock
	private MeetingTodoRepository meetingTodoRepository;
	@Mock
	private MeetingRealtimePublisher realtimePublisher;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");

	private MeetingNoteService service() {
		return new MeetingNoteService(
				domainLookupService,
				meetingNoteRepository,
				meetingTodoRepository,
				realtimePublisher);
	}

	@Test
	void createsAnEmptyNoteTheFirstTimeAMeetingIsOpened() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(meetingNoteRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(meetingNoteRepository.saveAndFlush(any(MeetingNote.class))).thenAnswer(returnsArgument());

		MeetingNoteResponse response = service().findNote(meetingId, organizationId);

		assertThat(response.content()).isEmpty();
		assertThat(response.version()).isZero();
	}

	@Test
	void hidesMeetingsThatBelongToAnotherOrganization() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());

		assertThatThrownBy(() -> service().findNote(meetingId, UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void savesTheNoteAndBroadcastsTheNewVersion() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(userId)).thenReturn(user());
		when(meetingNoteRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(note(3L, "old")));
		when(meetingNoteRepository.saveAndFlush(any(MeetingNote.class))).thenAnswer(returnsArgument());

		MeetingNoteResponse response = service().updateNote(
				meetingId,
				new UpdateMeetingNoteRequest(organizationId, userId, "<p>new</p>", 3L));

		assertThat(response.content()).isEqualTo("<p>new</p>");
		assertThat(response.version()).isEqualTo(4L);
		assertThat(response.updatedByName()).isEqualTo("Elif Demir");

		ArgumentCaptor<MeetingRoomEvent> event = ArgumentCaptor.forClass(MeetingRoomEvent.class);
		verify(realtimePublisher).publish(eq(meetingId), event.capture());
		assertThat(event.getValue().type()).isEqualTo(MeetingRoomEventType.NOTE_UPDATED);
		assertThat(event.getValue().note().version()).isEqualTo(4L);
	}

	@Test
	void rejectsSavesBasedOnAnOutdatedVersionWithoutBroadcasting() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(meetingNoteRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(note(5L, "current")));

		assertThatThrownBy(() -> service().updateNote(
				meetingId,
				new UpdateMeetingNoteRequest(organizationId, userId, "stale edit", 4L)))
				.isInstanceOf(ConflictException.class);

		verify(meetingNoteRepository, never()).saveAndFlush(any(MeetingNote.class));
		verify(realtimePublisher, never()).publish(any(), any());
	}

	@Test
	void addsNewTodosToTheEndOfTheList() {
		Meeting meeting = meeting();
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(userId)).thenReturn(user());
		when(meetingTodoRepository.countByMeetingId(meetingId)).thenReturn(2L);
		when(meetingTodoRepository.saveAndFlush(any(MeetingTodo.class))).thenAnswer(returnsArgument());
		when(meetingTodoRepository.findByMeetingIdOrderByDisplayOrderAsc(meetingId))
				.thenReturn(List.of(MeetingTodo.of(meeting, "Demo hazirla", user(), 2)));

		service().createTodo(meetingId, new CreateMeetingTodoRequest(organizationId, userId, "  Demo hazirla  "));

		ArgumentCaptor<MeetingTodo> saved = ArgumentCaptor.forClass(MeetingTodo.class);
		verify(meetingTodoRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTitle()).isEqualTo("Demo hazirla");
		assertThat(saved.getValue().getDisplayOrder()).isEqualTo(2);
		assertThat(saved.getValue().getDone()).isFalse();

		ArgumentCaptor<MeetingRoomEvent> event = ArgumentCaptor.forClass(MeetingRoomEvent.class);
		verify(realtimePublisher).publish(eq(meetingId), event.capture());
		assertThat(event.getValue().type()).isEqualTo(MeetingRoomEventType.TODOS_UPDATED);
		assertThat(event.getValue().todos()).hasSize(1);
	}

	private static <T> org.mockito.stubbing.Answer<T> returnsArgument() {
		return invocation -> invocation.getArgument(0);
	}

	private Meeting meeting() {
		Organization organization = new Organization();
		organization.setId(organizationId);
		organization.setName("Teknokent");

		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);
		meeting.setScheduledAt(Instant.parse("2026-09-15T16:00:00Z"));
		meeting.setDurationMinutes(45);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	private AppUser user() {
		AppUser user = new AppUser();
		user.setId(userId);
		user.setFullName("Elif Demir");
		return user;
	}

	private MeetingNote note(long version, String content) {
		MeetingNote note = MeetingNote.emptyFor(meeting());
		note.setId(UUID.randomUUID());
		note.setVersion(version);
		note.setContent(content);
		return note;
	}
}
