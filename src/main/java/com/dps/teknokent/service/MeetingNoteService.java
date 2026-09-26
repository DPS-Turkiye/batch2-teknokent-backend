package com.dps.teknokent.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingNoteService {

	private static final int MAX_TODO_TITLE = 1000;

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

	/**
	 * Puts the meeting report's action items on the Yapılacaklar list as AI tasks, as "task — owner · due" with
	 * the owner and the date left out when the report does not name them. A task already on the list is skipped,
	 * so the same report stored again, or copied to a group session's other meetings, adds nothing twice.
	 */
	@Transactional
	public List<MeetingTodoResponse> addReportActionItems(UUID meetingId, JsonNode report) {
		JsonNode items = report == null ? null : report.path("actionItems");
		if (items == null || !items.isArray() || items.isEmpty()) {
			return List.of();
		}
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		List<MeetingTodo> existing = meetingTodoRepository.findByMeetingIdOrderByDisplayOrderAsc(meetingId);
		Set<String> titles = new HashSet<>();
		existing.forEach(todo -> titles.add(titleKey(todo.getTitle())));
		int displayOrder = existing.size();
		boolean added = false;
		for (JsonNode item : items) {
			String title = actionItemTitle(item);
			if (title == null || !titles.add(titleKey(title))) {
				continue;
			}
			meetingTodoRepository.save(MeetingTodo.fromReport(meeting, title, displayOrder++));
			added = true;
		}
		if (!added) {
			return List.of();
		}
		meetingTodoRepository.flush();
		return publishTodos(meetingId);
	}

	static String actionItemTitle(JsonNode item) {
		String task = item.isTextual() ? item.asText() : firstText(item, "task", "title");
		task = TextUtils.normalizeOptional(task);
		if (task == null) {
			return null;
		}
		StringBuilder title = new StringBuilder(task);
		String owner = TextUtils.normalizeOptional(firstText(item, "owner"));
		if (owner != null) {
			title.append(" — ").append(owner);
		}
		String due = TextUtils.normalizeOptional(firstText(item, "due"));
		if (due != null) {
			title.append(" · ").append(due);
		}
		return title.length() > MAX_TODO_TITLE ? title.substring(0, MAX_TODO_TITLE) : title.toString();
	}

	private static String firstText(JsonNode item, String... fields) {
		for (String field : fields) {
			JsonNode value = item.path(field);
			if (value.isTextual() && !value.asText().isBlank()) {
				return value.asText();
			}
		}
		return null;
	}

	private static String titleKey(String title) {
		return title.trim().toLowerCase(Locale.ROOT);
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
