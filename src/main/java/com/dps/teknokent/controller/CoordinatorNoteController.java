package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dps.teknokent.dto.requests.AddCoordinatorNoteRequest;
import com.dps.teknokent.dto.responses.CoordinatorNoteResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.CoordinatorNoteService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Coordinator notes")
public class CoordinatorNoteController {

	private final CoordinatorNoteService noteService;

	@GetMapping("/api/coordinator/mentorships/{id}/notes")
	public List<CoordinatorNoteResponse> list(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return noteService.list(CurrentActor.resolve(actorId), id);
	}

	@PostMapping("/api/coordinator/mentorships/{id}/notes")
	@ResponseStatus(HttpStatus.CREATED)
	public CoordinatorNoteResponse add(@PathVariable UUID id, @Valid @RequestBody AddCoordinatorNoteRequest request) {
		return noteService.add(CurrentActor.resolve(request.actorId()), id, request);
	}

	@DeleteMapping("/api/coordinator/notes/{noteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID noteId, @RequestParam(required = false) UUID actorId) {
		noteService.delete(CurrentActor.resolve(actorId), noteId);
	}
}
