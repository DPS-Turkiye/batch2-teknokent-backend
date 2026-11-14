package com.dps.teknokent.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateMeetingTodoRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingTodoRequest;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingRoomEvent;
import com.dps.teknokent.dto.responses.MeetingTodoResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingNote;
import com.dps.teknokent.entity.MeetingTodo;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingNoteRepository;
import com.dps.teknokent.repository.MeetingTodoRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

/**
 * A meeting's shared note and to-do list. The organizationId every call names is the team's startup: on the proxy's
 * service credentials it comes from the session and is all the proxy sends, while a caller who is named (always, on a
 * bearer token) must also take part in the meeting, so nobody reaches another team's notes by naming its startup.
 */
@Service
@RequiredArgsConstructor
public class MeetingNoteService {

	private static final int MAX_TODO_TITLE = 1000;

	private final DomainLookupService domainLookupService;
	private final MeetingNoteRepository meetingNoteRepository;
	private final MeetingTodoRepository meetingTodoRepository;
	private final MeetingRealtimePublisher realtimePublisher;

	/**
	 * The note's last saved snapshot.
	 *
	 * @param callerId who reads; null only on the proxy's service credentials when it names nobody
	 */
	@Transactional(readOnly = true)
	public MeetingNoteResponse findNote(UUID meetingId, UUID organizationId, @Nullable UUID callerId) {
		Meeting meeting = requireMeetingInOrganization(meetingId, organizationId, callerId);
		return MeetingNoteResponse.fromEntity(meetingNoteRepository.findByMeetingId(meeting.getId())
				.orElseGet(() -> MeetingNote.emptyFor(meeting)));
	}

	/**
	 * Stores the snapshot of the shared note. The note is edited together through its collaborative document, so
	 * every editor's snapshot is the same merged text and the last one simply wins; the version only counts saves.
	 */
	@Transactional
	public MeetingNoteResponse updateNote(UUID meetingId, UUID authorId, UpdateMeetingNoteRequest request) {
		Meeting meeting = requireMeetingInOrganization(meetingId, request.organizationId(), null);
		AppUser author = requireParticipant(meeting, authorId);
		MeetingNote note = requireNote(meeting);

		note.setContent(request.content());
		note.setVersion(note.getVersion() + 1);
		note.setUpdatedBy(author);

		return MeetingNoteResponse.fromEntity(meetingNoteRepository.saveAndFlush(note));
	}

	/**
	 * The meeting's to-do list in order.
	 *
	 * @param callerId who reads; null only on the proxy's service credentials when it names nobody
	 */
	@Transactional(readOnly = true)
	public List<MeetingTodoResponse> findTodos(UUID meetingId, UUID organizationId, @Nullable UUID callerId) {
		requireMeetingInOrganization(meetingId, organizationId, callerId);
		return todoResponses(meetingId);
	}

	@Transactional
	public List<MeetingTodoResponse> createTodo(UUID meetingId, UUID authorId, CreateMeetingTodoRequest request) {
		Meeting meeting = requireMeetingInOrganization(meetingId, request.organizationId(), null);
		AppUser author = requireParticipant(meeting, authorId);
		int displayOrder = (int) meetingTodoRepository.countByMeetingId(meetingId);
		meetingTodoRepository.saveAndFlush(MeetingTodo.of(
				meeting,
				request.title().trim(),
				author,
				displayOrder));
		return publishTodos(meetingId);
	}

	/**
	 * Renames a to-do or ticks it off.
	 *
	 * @param callerId who changes it; null only on the proxy's service credentials when it names nobody
	 */
	@Transactional
	public List<MeetingTodoResponse> updateTodo(
			UUID meetingId, UUID todoId, @Nullable UUID callerId, UpdateMeetingTodoRequest request) {
		requireMeetingInOrganization(meetingId, request.organizationId(), callerId);
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

	/**
	 * Removes a to-do.
	 *
	 * @param callerId who deletes it; null only on the proxy's service credentials when it names nobody
	 */
	@Transactional
	public List<MeetingTodoResponse> deleteTodo(UUID meetingId, UUID todoId, UUID organizationId,
			@Nullable UUID callerId) {
		requireMeetingInOrganization(meetingId, organizationId, callerId);
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
	public List<MeetingTodoResponse> addReportActionItems(UUID meetingId, List<MeetingReportActionItem> items) {
		if (items == null || items.isEmpty()) {
			return List.of();
		}
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		List<MeetingTodo> existing = meetingTodoRepository.findByMeetingIdOrderByDisplayOrderAsc(meetingId);
		Set<String> titles = new HashSet<>();
		existing.forEach(todo -> titles.add(titleKey(todo.getTitle())));
		int displayOrder = existing.size();
		boolean added = false;
		for (MeetingReportActionItem item : items) {
			String title = item == null ? null : actionItemTitle(item);
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

	static @Nullable String actionItemTitle(MeetingReportActionItem item) {
		String task = TextUtils.normalizeOptional(item.task());
		if (task == null) {
			return null;
		}
		StringBuilder title = new StringBuilder(task);
		String owner = TextUtils.normalizeOptional(item.owner());
		if (owner != null) {
			title.append(" — ").append(owner);
		}
		String due = TextUtils.normalizeOptional(item.due());
		if (due != null) {
			title.append(" · ").append(due);
		}
		return title.length() > MAX_TODO_TITLE ? title.substring(0, MAX_TODO_TITLE) : title.toString();
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

	private Meeting requireMeetingInOrganization(UUID meetingId, UUID organizationId, @Nullable UUID callerId) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		if (!meeting.getOrganization().getId().equals(organizationId)) {
			throw new ResourceNotFoundException("Meeting not found: " + meetingId);
		}
		if (callerId != null) {
			requireParticipant(meeting, callerId);
		}
		return meeting;
	}

	/** A member of the meeting's team or its mentor; anyone else is told the meeting does not exist. */
	private AppUser requireParticipant(Meeting meeting, UUID userId) {
		AppUser user = domainLookupService.requireUser(userId);
		boolean teamMember = user.getOrganization() != null
				&& user.getOrganization().getId().equals(meeting.getOrganization().getId());
		boolean mentor = meeting.getMentor() != null && meeting.getMentor().getId().equals(userId);
		if (!teamMember && !mentor) {
			throw new ResourceNotFoundException("Meeting not found: " + meeting.getId());
		}
		return user;
	}
}
