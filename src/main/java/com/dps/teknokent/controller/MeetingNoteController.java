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
import com.dps.teknokent.dto.requests.UpdateMeetingNoteRequest;
import com.dps.teknokent.dto.requests.UpdateMeetingTodoRequest;
import com.dps.teknokent.dto.responses.MeetingNoteDocumentResponse;
import com.dps.teknokent.dto.responses.MeetingNoteResponse;
import com.dps.teknokent.dto.responses.MeetingTodoResponse;
import com.dps.teknokent.service.MeetingNoteDocumentService;
import com.dps.teknokent.service.MeetingNoteService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/meetings/{meetingId}")
@RequiredArgsConstructor
@Tag(name = "Meeting notes")
public class MeetingNoteController {

	private final MeetingNoteService meetingNoteService;
	private final MeetingNoteDocumentService meetingNoteDocumentService;

	@GetMapping("/note")
	public MeetingNoteResponse findNote(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId) {
		return meetingNoteService.findNote(meetingId, organizationId);
	}

	@GetMapping("/note/document")
	public MeetingNoteDocumentResponse findNoteDocument(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId) {
		return meetingNoteDocumentService.findDocument(meetingId, organizationId);
	}

	@PutMapping("/note")
	public MeetingNoteResponse updateNote(
			@PathVariable UUID meetingId,
			@Valid @RequestBody UpdateMeetingNoteRequest request) {
		return meetingNoteService.updateNote(meetingId, request);
	}

	@GetMapping("/todos")
	public List<MeetingTodoResponse> findTodos(
			@PathVariable UUID meetingId,
			@RequestParam UUID organizationId) {
		return meetingNoteService.findTodos(meetingId, organizationId);
	}

	@PostMapping("/todos")
	public List<MeetingTodoResponse> createTodo(
			@PathVariable UUID meetingId,
			@Valid @RequestBody CreateMeetingTodoRequest request) {
		return meetingNoteService.createTodo(meetingId, request);
	}

	@PatchMapping("/todos/{todoId}")
	public List<MeetingTodoResponse> updateTodo(
			@PathVariable UUID meetingId,
			@PathVariable UUID todoId,
			@Valid @RequestBody UpdateMeetingTodoRequest request) {
		return meetingNoteService.updateTodo(meetingId, todoId, request);
	}

	@DeleteMapping("/todos/{todoId}")
	public List<MeetingTodoResponse> deleteTodo(
			@PathVariable UUID meetingId,
			@PathVariable UUID todoId,
			@RequestParam UUID organizationId) {
		return meetingNoteService.deleteTodo(meetingId, todoId, organizationId);
	}
}
