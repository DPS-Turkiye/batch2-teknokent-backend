package com.dps.teknokent.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

	private static final Set<String> PDF_TYPES = Set.of("application/pdf");
	private static final Set<String> DOC_TYPES = Set.of(
			"application/pdf",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
	private static final byte[] PDF_MAGIC = new byte[] { '%', 'P', 'D', 'F', '-' };
	private static final Set<String> DANGEROUS_ARCHIVE_SUFFIXES = Set.of(
			".js", ".jar", ".exe", ".dll", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".msi");
	private static final int MAX_DOCX_ENTRIES = 2_000;
	private static final long MAX_DOCX_TOTAL_UNCOMPRESSED_BYTES = 40L * 1024L * 1024L;
	private static final long MAX_DOCX_ENTRY_UNCOMPRESSED_BYTES = 20L * 1024L * 1024L;
	private static final int MAX_DOCX_COMPRESSION_RATIO = 200;
	private static final int ZIP_READ_BUFFER = 8 * 1024;
	private static final int PDF_EOF_SCAN_BYTES = 4 * 1024;

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
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					file == null ? null : file.getOriginalFilename(),
					file == null ? null : file.getSize(),
					"io-read-failure",
					"The uploaded file could not be read.");
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
		Long fileSize = bytes == null ? null : (long) bytes.length;
		if (bytes == null || bytes.length == 0) {
			throw rejectUpload(userId, organizationId, kindValue, originalFilename, fileSize, "empty-file", "A file is required.");
		}
		DocumentKind kind;
		try {
			kind = DocumentKind.from(kindValue);
		} catch (InvalidRequestException error) {
			throw rejectUpload(userId, organizationId, kindValue, originalFilename, fileSize, "unknown-kind", error.getMessage());
		}
		if (bytes.length > kind.maxBytes()) {
			throw rejectUpload(userId, organizationId, kindValue, originalFilename, fileSize, "size-limit", "The file is too large.");
		}
		String filename = originalFilename == null ? "" : originalFilename.trim();
		if (filename.isBlank()) {
			throw rejectUpload(userId, organizationId, kindValue, originalFilename, fileSize, "missing-filename", "A file name is required.");
		}
		String extension = extensionOf(filename);
		if (kind.pdfOnly() && !extension.equals("pdf")) {
			throw rejectUpload(userId, organizationId, kindValue, filename, fileSize, "extension-not-pdf", "Please upload a PDF.");
		}
		if (!extension.equals("pdf") && !extension.equals("docx")) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"extension-not-allowed",
					"Please upload a PDF or DOCX file.");
		}
		String resolvedType = resolveContentType(contentType, extension);
		if (kind.pdfOnly() && !PDF_TYPES.contains(resolvedType)) {
			throw rejectUpload(userId, organizationId, kindValue, filename, fileSize, "mime-not-pdf", "Please upload a PDF.");
		}
		if (!kind.pdfOnly() && !DOC_TYPES.contains(resolvedType)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"mime-not-allowed",
					"Please upload a PDF or DOCX file.");
		}
		validateFileContent(userId, organizationId, kindValue, filename, extension, bytes);

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

	private void validateFileContent(
			UUID userId,
			UUID organizationId,
			String kindValue,
			String filename,
			String extension,
			byte[] bytes) {
		if ("pdf".equals(extension)) {
			if (!hasPdfSignature(bytes) || !hasPdfEofMarker(bytes)) {
				throw rejectUpload(
						userId,
						organizationId,
						kindValue,
						filename,
						(long) bytes.length,
						"invalid-pdf-structure",
						"The uploaded PDF is invalid.");
			}
			return;
		}

		if ("docx".equals(extension) && !isSafeDocx(bytes)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					(long) bytes.length,
					"invalid-or-unsafe-docx",
					"The uploaded DOCX file is invalid.");
		}
	}

	private static boolean hasPdfSignature(byte[] bytes) {
		if (bytes.length < PDF_MAGIC.length) {
			return false;
		}
		for (int index = 0; index < PDF_MAGIC.length; index++) {
			if (bytes[index] != PDF_MAGIC[index]) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasPdfEofMarker(byte[] bytes) {
		int scanLength = Math.min(bytes.length, PDF_EOF_SCAN_BYTES);
		String tail = new String(bytes, bytes.length - scanLength, scanLength, java.nio.charset.StandardCharsets.ISO_8859_1);
		return tail.contains("%%EOF");
	}

	private static boolean isSafeDocx(byte[] bytes) {
		boolean hasContentTypes = false;
		boolean hasMainDocument = false;
		int entries = 0;
		long totalUncompressedBytes = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entries++;
				if (entries > MAX_DOCX_ENTRIES) {
					return false;
				}
				if (entry.isDirectory()) {
					continue;
				}
				String name = entry.getName();
				if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
					return false;
				}
				String lower = name.toLowerCase(Locale.ROOT);
				if ("[content_types].xml".equals(lower)) {
					hasContentTypes = true;
				}
				if ("word/document.xml".equals(lower)) {
					hasMainDocument = true;
				}
				if (lower.contains("vbaproject.bin") || hasDangerousSuffix(lower)) {
					return false;
				}

				long entryBytes = 0;
				byte[] buffer = new byte[ZIP_READ_BUFFER];
				int read;
				while ((read = zip.read(buffer)) != -1) {
					entryBytes += read;
					totalUncompressedBytes += read;
					if (entryBytes > MAX_DOCX_ENTRY_UNCOMPRESSED_BYTES
							|| totalUncompressedBytes > MAX_DOCX_TOTAL_UNCOMPRESSED_BYTES) {
						return false;
					}
				}

				long declaredUncompressed = entry.getSize();
				long declaredCompressed = entry.getCompressedSize();
				if (declaredUncompressed > 0 && declaredCompressed > 0) {
					long ratio = declaredUncompressed / Math.max(1, declaredCompressed);
					if (ratio > MAX_DOCX_COMPRESSION_RATIO) {
						return false;
					}
				}
			}
		} catch (IOException error) {
			return false;
		}
		return hasContentTypes && hasMainDocument;
	}

	private InvalidRequestException rejectUpload(
			UUID userId,
			UUID organizationId,
			String kindValue,
			String filename,
			Long sizeBytes,
			String reason,
			String message) {
		log.warn(
				"Blocked document upload: reason={}, userId={}, organizationId={}, kind={}, filename={}, sizeBytes={}",
				reason,
				userId,
				organizationId,
				kindValue,
				filename,
				sizeBytes);
		return new InvalidRequestException(message);
	}

	private static boolean hasDangerousSuffix(String filename) {
		for (String suffix : DANGEROUS_ARCHIVE_SUFFIXES) {
			if (filename.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}
}
