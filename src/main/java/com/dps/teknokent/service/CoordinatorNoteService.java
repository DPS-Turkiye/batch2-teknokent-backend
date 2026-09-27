package com.dps.teknokent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.AddCoordinatorNoteRequest;
import com.dps.teknokent.dto.responses.CoordinatorNoteResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorNote;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorNoteRepository;

import lombok.RequiredArgsConstructor;

/** "Koordinatör notu": notes on a mentorship that only its program's coordinators see. Newest first. */
@Service
@RequiredArgsConstructor
public class CoordinatorNoteService {

	private final CoordinatorMentorshipService coordinatorMentorshipService;
	private final CoordinatorNoteRepository noteRepository;
	private final AppUserRepository appUserRepository;

	@Transactional(readOnly = true)
	public List<CoordinatorNoteResponse> list(UUID actorId, UUID mentorshipId) {
		coordinatorMentorshipService.requireManagedMentorship(actorId, mentorshipId);
		List<CoordinatorNote> notes = noteRepository.findByMentorshipIdOrderByCreatedAtDesc(mentorshipId);
		Map<UUID, String> names = appUserRepository
				.findAllById(notes.stream().map(CoordinatorNote::getAuthorId).distinct().toList()).stream()
				.collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
		return notes.stream()
				.map(note -> response(note, names.get(note.getAuthorId()), actorId))
				.toList();
	}

	@Transactional
	public CoordinatorNoteResponse add(UUID actorId, UUID mentorshipId, AddCoordinatorNoteRequest request) {
		coordinatorMentorshipService.requireManagedMentorship(actorId, mentorshipId);
		CoordinatorNote note = new CoordinatorNote();
		note.setId(UUID.randomUUID());
		note.setMentorshipId(mentorshipId);
		note.setAuthorId(actorId);
		note.setBody(request.body().strip());
		CoordinatorNote saved = noteRepository.saveAndFlush(note);
		String author = appUserRepository.findById(actorId).map(AppUser::getFullName).orElse(null);
		return response(saved, author, actorId);
	}

	/** Only the author removes a note, and only while they still coordinate that mentorship's program. */
	@Transactional
	public void delete(UUID actorId, UUID noteId) {
		CoordinatorNote note = noteRepository.findById(noteId)
				.orElseThrow(() -> new ResourceNotFoundException("Coordinator note not found: " + noteId));
		coordinatorMentorshipService.requireManagedMentorship(actorId, note.getMentorshipId());
		if (!note.getAuthorId().equals(actorId)) {
			throw new InvalidRequestException("Yalnızca kendi notunu silebilirsin.");
		}
		noteRepository.delete(note);
	}

	private static CoordinatorNoteResponse response(CoordinatorNote note, String authorName, UUID viewerId) {
		return new CoordinatorNoteResponse(
				note.getId(), note.getMentorshipId(), authorName, note.getBody(), note.getCreatedAt(),
				note.getAuthorId().equals(viewerId));
	}
}
