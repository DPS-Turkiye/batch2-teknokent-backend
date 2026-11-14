package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.CreateMeetingTodoRequest;
import com.dps.teknokent.dto.requests.CreateTranscriptUploadRequest;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingTodoRequest;
import com.dps.teknokent.dto.responses.MeetingAssistantShareResponse;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.dto.responses.MeetingTodoResponse;
import com.dps.teknokent.dto.responses.TranscriptUploadUrlResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.MeetingAssistantShareService;
import com.dps.teknokent.service.MeetingNoteDocumentService;
import com.dps.teknokent.service.MeetingNoteService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * A bearer caller is always themselves. On the proxy's service credentials the ids in the request name the caller:
 * {@code userId} on the writes that carry it, the optional {@code actorId} elsewhere, and {@code viewerId} on the
 * mentor's notebook. The organizationId is the team's startup; the service checks a named caller takes part in it.
 */
@RestController
@RequestMapping("/api/meetings/{meetingId}")
@RequiredArgsConstructor
@Tag(name = "Meeting notes")
public class MeetingNoteController {

	private final MeetingNoteService meetingNoteService;
	private final MeetingNoteDocumentService meetingNoteDocumentService;
	private final MeetingAssistantShareService meetingAssistantShareService;

	@GetMapping("/note")
	public MeetingNoteResponse findNote(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return meetingNoteService.findNote(meetingId, organizationId, CurrentActor.resolveOptional(actorId));
	}

	@GetMapping("/note/document")
	public MeetingNoteDocumentResponse findNoteDocument(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return meetingNoteDocumentService.findDocument(meetingId, organizationId,
				CurrentActor.resolveOptional(actorId));
	}

	@PutMapping("/note")
	public MeetingNoteResponse updateNote(
			@PathVariable UUID meetingId,
			@Valid @RequestBody UpdateMeetingNoteRequest request) {
		return meetingNoteService.updateNote(meetingId, CurrentActor.resolve(request.userId()), request);
	}

	@GetMapping("/todos")
	public List<MeetingTodoResponse> findTodos(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return meetingNoteService.findTodos(meetingId, organizationId, CurrentActor.resolveOptional(actorId));
	}

	@PostMapping("/todos")
	public List<MeetingTodoResponse> createTodo(
			@PathVariable UUID meetingId,
			@Valid @RequestBody CreateMeetingTodoRequest request) {
		return meetingNoteService.createTodo(meetingId, CurrentActor.resolve(request.userId()), request);
	}

	@PatchMapping("/todos/{todoId}")
	public List<MeetingTodoResponse> updateTodo(
			@PathVariable UUID meetingId,
			@PathVariable UUID todoId,
			@RequestParam(required = false) UUID actorId,
			@Valid @RequestBody UpdateMeetingTodoRequest request) {
		return meetingNoteService.updateTodo(meetingId, todoId, CurrentActor.resolveOptional(actorId), request);
	}

	@DeleteMapping("/todos/{todoId}")
	public List<MeetingTodoResponse> deleteTodo(
			@PathVariable UUID meetingId,
			@PathVariable UUID todoId,
			@RequestParam UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return meetingNoteService.deleteTodo(meetingId, todoId, organizationId, CurrentActor.resolveOptional(actorId));
	}

	@GetMapping("/assistant-share")
	public MeetingAssistantShareResponse findShare(
			@PathVariable UUID meetingId,
			@RequestParam(required = false) UUID organizationId,
			@RequestParam(required = false) UUID viewerId) {
		return meetingAssistantShareService.find(meetingId, organizationId, CurrentActor.resolveOptional(viewerId));
	}

	@PostMapping("/assistant-share")
	public MeetingAssistantShareResponse publishShare(
			@PathVariable UUID meetingId,
			@Valid @RequestBody PublishMeetingAssistantShareRequest request) {
		return meetingAssistantShareService.publish(meetingId, new PublishMeetingAssistantShareRequest(
				CurrentActor.resolve(request.actorId()), request.kind(), request.transcript(), request.report()));
	}

	@PostMapping("/assistant-share/transcript-upload")
	public TranscriptUploadUrlResponse createTranscriptUpload(
			@PathVariable UUID meetingId,
			@RequestBody CreateTranscriptUploadRequest request) {
		return meetingAssistantShareService.createTranscriptUpload(meetingId, CurrentActor.resolve(request.actorId()));
	}

	@PostMapping("/assistant-share/report-upload")
	public TranscriptUploadUrlResponse createReportUpload(
			@PathVariable UUID meetingId,
			@RequestBody CreateTranscriptUploadRequest request) {
		return meetingAssistantShareService.createReportUpload(meetingId, CurrentActor.resolve(request.actorId()));
	}
}
