package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateMeetingTodoRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingTodoRequest;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;
import com.dps.teknokent.dto.responses.MeetingTodoResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingNote;
import com.dps.teknokent.entity.MeetingTodo;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingTodoRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingNoteService {

	private final DomainLookupService domainLookupService;
	private final MeetingNoteRepository meetingNoteRepository;
	private final MeetingTodoRepository meetingTodoRepository;
	private final MeetingRealtimePublisher realtimePublisher;

	@Transactional(readOnly = true)
	public MeetingNoteResponse findNote(UUID meetingId, UUID organizationId) {
		Meeting meeting = requireMeetingInOrganization(meetingId, organizationId);
		return MeetingNoteResponse.fromEntity(meetingNoteRepository.findByMeetingId(meeting.getId())
				.orElseGet(() -> MeetingNote.emptyFor(meeting)));
	}

	@Transactional
	public MeetingNoteResponse updateNote(UUID meetingId, UpdateMeetingNoteRequest request) {
		Meeting meeting = requireMeetingInOrganization(meetingId, request.organizationId());
		MeetingNote note = requireNote(meeting);

		note.setContent(request.content());
		note.setVersion(note.getVersion() + 1);
		note.setUpdatedBy(domainLookupService.requireUser(request.userId()));

		return MeetingNoteResponse.fromEntity(meetingNoteRepository.saveAndFlush(note));
	}

	@Transactional(readOnly = true)
	public List<MeetingTodoResponse> findTodos(UUID meetingId, UUID organizationId) {
		requireMeetingInOrganization(meetingId, organizationId);
		return todoResponses(meetingId);
	}

	@Transactional
	public List<MeetingTodoResponse> createTodo(UUID meetingId, CreateMeetingTodoRequest request) {
		Meeting meeting = requireMeetingInOrganization(meetingId, request.organizationId());
		int displayOrder = (int) meetingTodoRepository.countByMeetingId(meetingId);
		meetingTodoRepository.saveAndFlush(MeetingTodo.of(
				meeting,
				request.title().trim(),
				domainLookupService.requireUser(request.userId()),
				displayOrder));
		return publishTodos(meetingId);
	}

	@Transactional
	public List<MeetingTodoResponse> updateTodo(UUID meetingId, UUID todoId, UpdateMeetingTodoRequest request) {
		requireMeetingInOrganization(meetingId, request.organizationId());
		MeetingTodo todo = requireTodo(meetingId, todoId);

		String title = TextUtils.normalizeOptional(request.title());
		if (title != null) {
			todo.setTitle(title);
		}
		if (request.done() != null) {
			todo.setDone(request.done());
		}

		meetingTodoRepository.saveAndFlush(todo);
		return publishTodos(meetingId);
	}

	@Transactional
	public List<MeetingTodoResponse> deleteTodo(UUID meetingId, UUID todoId, UUID organizationId) {
		requireMeetingInOrganization(meetingId, organizationId);
		meetingTodoRepository.delete(requireTodo(meetingId, todoId));
		meetingTodoRepository.flush();
		return publishTodos(meetingId);
	}

	private List<MeetingTodoResponse> publishTodos(UUID meetingId) {
		List<MeetingTodoResponse> todos = todoResponses(meetingId);
		realtimePublisher.publish(meetingId, MeetingRoomEvent.todosUpdated(todos));
		return todos;
	}

	private List<MeetingTodoResponse> todoResponses(UUID meetingId) {
		return meetingTodoRepository.findByMeetingIdOrderByDisplayOrderAsc(meetingId).stream()
				.map(MeetingTodoResponse::fromEntity)
				.toList();
	}

	private MeetingNote requireNote(Meeting meeting) {
		return meetingNoteRepository.findByMeetingId(meeting.getId())
				.orElseGet(() -> meetingNoteRepository.saveAndFlush(MeetingNote.emptyFor(meeting)));
	}

	private MeetingTodo requireTodo(UUID meetingId, UUID todoId) {
		return meetingTodoRepository.findByIdAndMeetingId(todoId, meetingId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting todo not found: " + todoId));
	}

	private Meeting requireMeetingInOrganization(UUID meetingId, UUID organizationId) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		if (!meeting.getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("Meeting not found: " + meetingId);
		}
		return meeting;
	}
}
