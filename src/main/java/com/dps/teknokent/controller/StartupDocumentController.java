package com.dps.teknokent.controller;

import com.dps.teknokent.security.CurrentActor;
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

import com.dps.teknokent.dto.requests.ShareDocumentRequest;
import com.dps.teknokent.dto.responses.DocumentDownloadResponse;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.dto.responses.OrganizationDocumentResponse;
import com.dps.teknokent.service.DocumentRequestService;
import com.dps.teknokent.service.StartupDocumentService;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Startup Documents")
public class StartupDocumentController {

	private final StartupDocumentService startupDocumentService;
	private final DocumentRequestService documentRequestService;

	@GetMapping("/api/organizations/{organizationId}/document-requests")
	public List<DocumentRequestItemResponse> listDocumentRequests(
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return documentRequestService.listForOrganization(organizationId, CurrentActor.resolve(actorId));
	}

	@GetMapping("/api/organizations/{organizationId}/documents")
	public List<OrganizationDocumentResponse> list(
			@PathVariable UUID organizationId,
			@RequestParam(required = false) UUID actorId) {
		return startupDocumentService.listForOrganization(organizationId, CurrentActor.resolve(actorId));
	}

	@GetMapping("/api/documents/{id}/download-url")
	public DocumentDownloadResponse downloadUrl(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return startupDocumentService.downloadUrl(id, CurrentActor.resolve(actorId));
	}

	@PostMapping("/api/documents/{id}/shares")
	public OrganizationDocumentResponse share(@PathVariable UUID id, @Valid @RequestBody ShareDocumentRequest request) {
		return startupDocumentService.share(id,
				new ShareDocumentRequest(CurrentActor.resolve(request.actorId()), request.mentorId()));
	}

	@PostMapping("/api/documents/{id}/review")
	public OrganizationDocumentResponse review(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		return startupDocumentService.review(id, CurrentActor.resolve(actorId));
	}

	@DeleteMapping("/api/documents/{id}/shares/{mentorId}")
	public OrganizationDocumentResponse unshare(
			@PathVariable UUID id,
			@PathVariable UUID mentorId,
			@RequestParam(required = false) UUID actorId) {
		return startupDocumentService.unshare(id, mentorId, CurrentActor.resolve(actorId));
	}

	@DeleteMapping("/api/documents/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID actorId) {
		startupDocumentService.delete(id, CurrentActor.resolve(actorId));
	}
}
