package com.dps.teknokent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.dto.responses.StoredDocumentResponse;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.DocumentService;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Documents")
public class DocumentController {

	private final DocumentService documentService;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public StoredDocumentResponse upload(
			@RequestParam("kind") String kind,
			@RequestParam(required = false) UUID userId,
			@RequestParam(required = false) UUID meetingId,
			@RequestPart("file") MultipartFile file) {
		return documentService.storeForUser(resolveUserId(userId), kind, file, meetingId);
	}

	@GetMapping
	public List<StoredDocumentResponse> list(@RequestParam(required = false) UUID userId) {
		return documentService.listForUser(resolveUserId(userId));
	}

	private static UUID resolveUserId(UUID requestedUserId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		return requestedUserId;
	}
}
