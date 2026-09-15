package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AttachDocumentRequestUploadRequest;
import com.dps.teknokent.dto.requests.ReplaceDocumentRequestsRequest;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.dto.responses.DocumentRequestUploadResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.DocumentRequestService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/document-requests")
@RequiredArgsConstructor
@Tag(name = "Document Requests")
public class DocumentRequestController {

	private final DocumentRequestService documentRequestService;

	@GetMapping
	public List<DocumentRequestItemResponse> list(
			@RequestParam(required = false) UUID mentorshipRequestId,
			@RequestParam(required = false) UUID meetingId,
			@RequestParam(required = false) UUID mentorshipId) {
		return documentRequestService.list(mentorshipRequestId, meetingId, mentorshipId);
	}

	@PutMapping
	public List<DocumentRequestItemResponse> replace(
			@RequestParam(required = false) UUID mentorshipRequestId,
			@RequestParam(required = false) UUID meetingId,
			@RequestParam(required = false) UUID mentorshipId,
			@RequestParam(required = false) UUID programId,
			@Valid @RequestBody ReplaceDocumentRequestsRequest request) {
		UUID actorId = CurrentActor.resolve(request.actorId());
		if (mentorshipRequestId != null) {
			return documentRequestService.replaceOnRequest(
					mentorshipRequestId, actorId, request.titles(), request.dueDate());
		}
		if (meetingId != null) {
			return documentRequestService.replaceOnMeeting(meetingId, actorId, request.titles(), request.dueDate());
		}
		if (mentorshipId != null) {
			return documentRequestService.replaceOnMentorship(
					mentorshipId, actorId, request.titles(), request.dueDate());
		}
		if (programId != null) {
			return documentRequestService.replaceOnProgram(programId, actorId, request.titles(), request.dueDate());
		}
		throw new com.dps.teknokent.exception.InvalidRequestException(
				"Provide exactly one of mentorshipRequestId, meetingId, mentorshipId, or programId");
	}

	@PostMapping("/{itemId}/uploads")
	@ResponseStatus(HttpStatus.CREATED)
	public DocumentRequestUploadResponse attachUpload(
			@PathVariable UUID itemId,
			@Valid @RequestBody AttachDocumentRequestUploadRequest request) {
		return documentRequestService.attachUpload(itemId, request);
	}

	@DeleteMapping("/uploads/{uploadId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteUpload(
			@PathVariable UUID uploadId,
			@RequestParam UUID actorId) {
		documentRequestService.deleteUpload(uploadId, actorId);
	}
}
