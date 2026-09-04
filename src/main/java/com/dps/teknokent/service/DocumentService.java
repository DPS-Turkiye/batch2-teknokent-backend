package com.dps.teknokent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.dto.responses.StoredDocumentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentService {

	private static final Set<String> PDF_TYPES = Set.of("application/pdf");
	private static final Set<String> DOC_TYPES = Set.of(
			"application/pdf",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document");

	private final AppUserRepository appUserRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final ObjectStorage objectStorage;

	public StoredDocumentResponse storeForUser(UUID userId, String kindValue, MultipartFile file) {
		if (userId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
		UUID organizationId = user.getOrganization() == null ? null : user.getOrganization().getId();
		try {
			return store(
					user.getId(),
					organizationId,
					kindValue,
					file.getOriginalFilename(),
					file.getContentType(),
					file.getBytes());
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded file could not be read.");
		}
	}

	public List<StoredDocumentResponse> listForUser(UUID userId) {
		if (userId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		return storedDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.map(StoredDocumentResponse::fromEntity)
				.toList();
	}

	public StoredDocumentResponse store(
			UUID userId,
			UUID organizationId,
			String kindValue,
			String originalFilename,
			String contentType,
			byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new InvalidRequestException("A file is required.");
		}
		DocumentKind kind = DocumentKind.from(kindValue);
		if (bytes.length > kind.maxBytes()) {
			throw new InvalidRequestException("The file is too large.");
		}
		String filename = originalFilename == null ? "" : originalFilename.trim();
		if (filename.isBlank()) {
			throw new InvalidRequestException("A file name is required.");
		}
		String extension = extensionOf(filename);
		if (kind.pdfOnly() && !extension.equals("pdf")) {
			throw new InvalidRequestException("Please upload a PDF.");
		}
		if (!extension.equals("pdf") && !extension.equals("docx")) {
			throw new InvalidRequestException("Please upload a PDF or DOCX file.");
		}
		String resolvedType = resolveContentType(contentType, extension);
		if (kind.pdfOnly() && !PDF_TYPES.contains(resolvedType)) {
			throw new InvalidRequestException("Please upload a PDF.");
		}
		if (!kind.pdfOnly() && !DOC_TYPES.contains(resolvedType)) {
			throw new InvalidRequestException("Please upload a PDF or DOCX file.");
		}

		UUID id = UUID.randomUUID();
		String keyPrefix = userId != null
				? "documents/" + userId + "/" + kind.value()
				: "documents/org/" + organizationId + "/" + kind.value();
		String key = keyPrefix + "/" + id + "-" + sanitize(filename);

		objectStorage.put(key, bytes, resolvedType);

		StoredDocument document = new StoredDocument();
		document.setId(id);
		document.setUserId(userId);
		document.setOrganizationId(organizationId);
		document.setKind(kind.value());
		document.setOriginalFilename(filename);
		document.setContentType(resolvedType);
		document.setSizeBytes(bytes.length);
		document.setS3Key(key);
		document.setCreatedAt(Instant.now());
		return StoredDocumentResponse.fromEntity(storedDocumentRepository.save(document));
	}

	private static String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) {
			return "";
		}
		return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String resolveContentType(String contentType, String extension) {
		if (contentType != null && !contentType.isBlank() && !contentType.equals("application/octet-stream")) {
			return contentType.toLowerCase(Locale.ROOT);
		}
		return extension.equals("docx")
				? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
				: "application/pdf";
	}

	private static String sanitize(String filename) {
		String cleaned = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (cleaned.length() > 80) {
			return cleaned.substring(cleaned.length() - 80);
		}
		return cleaned;
	}
}
