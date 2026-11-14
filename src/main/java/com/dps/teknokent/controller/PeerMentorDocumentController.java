package com.dps.teknokent.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.domain.PeerEvidenceType;
import com.dps.teknokent.dto.requests.CreatePeerMentorLinkRequest;
import com.dps.teknokent.dto.requests.UpdatePeerMentorDocumentRequest;
import com.dps.teknokent.dto.responses.PeerMentorDocumentResponse;
import com.dps.teknokent.security.CurrentActor;
import com.dps.teknokent.service.PeerMentorDocumentService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** The peer mentor's evidence, files and links (wizard step "Belgeler ve kanıtlar"); ODTÜ only, like all of /api/peer-mentoring. */
@RestController
@RequestMapping("/api/peer-mentoring")
@RequiredArgsConstructor
@Tag(name = "Peer mentoring")
public class PeerMentorDocumentController {

	private final PeerMentorDocumentService peerMentorDocumentService;

	@GetMapping("/profile/documents")
	public List<PeerMentorDocumentResponse> mine(@RequestParam(required = false) UUID actorId) {
		return peerMentorDocumentService.mine(CurrentActor.resolve(actorId));
	}

	@PostMapping(value = "/profile/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public PeerMentorDocumentResponse upload(
			@RequestParam PeerEvidenceType evidenceType,
			@RequestParam PeerDocumentVisibility visibility,
			@RequestParam(required = false) UUID actorId,
			@RequestPart("file") MultipartFile file) {
		return peerMentorDocumentService.upload(CurrentActor.resolve(actorId), evidenceType, visibility, file);
	}

	/** A portfolio or project link ("Portfolyo ve bağlantılar"); it lists and changes like a file. */
	@PostMapping("/profile/links")
	@ResponseStatus(HttpStatus.CREATED)
	public PeerMentorDocumentResponse addLink(@Valid @RequestBody CreatePeerMentorLinkRequest request) {
		return peerMentorDocumentService.addLink(CurrentActor.resolve(request.actorId()), request.url(),
				request.visibility());
	}

	@PatchMapping("/profile/documents/{documentId}")
	public PeerMentorDocumentResponse updateVisibility(@PathVariable UUID documentId,
			@Valid @RequestBody UpdatePeerMentorDocumentRequest request) {
		return peerMentorDocumentService.updateVisibility(CurrentActor.resolve(request.actorId()), documentId,
				request.visibility());
	}

	@DeleteMapping("/profile/documents/{documentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID documentId, @RequestParam(required = false) UUID actorId) {
		peerMentorDocumentService.delete(CurrentActor.resolve(actorId), documentId);
	}

	/** All of them for the mentor, their coordinators and admins; the PROFILE ones for students of their program. */
	@GetMapping("/mentors/{mentorId}/documents")
	public List<PeerMentorDocumentResponse> ofMentor(@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		return peerMentorDocumentService.ofMentor(CurrentActor.resolve(actorId), mentorId);
	}

	/** Always an attachment with nosniff, so an uploaded file is never rendered in the app's origin. */
	@GetMapping("/documents/{documentId}/content")
	public ResponseEntity<byte[]> content(@PathVariable UUID documentId,
			@RequestParam(required = false) UUID actorId) {
		PeerMentorDocumentService.DocumentContent content = peerMentorDocumentService
				.content(CurrentActor.resolve(actorId), documentId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(content.filename(), StandardCharsets.UTF_8)
						.build()
						.toString())
				.header("X-Content-Type-Options", "nosniff")
				.cacheControl(CacheControl.noStore().cachePrivate())
				.body(content.bytes());
	}
}
