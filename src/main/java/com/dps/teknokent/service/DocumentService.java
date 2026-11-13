package com.dps.teknokent.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.dto.responses.StoredDocumentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

	private static final Set<String> PDF_TYPES = Set.of("application/pdf");
	private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
	private static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	private static final String PPTX_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
	private static final Set<String> DOC_TYPES = Set.of("application/pdf", DOCX_TYPE, XLSX_TYPE, PPTX_TYPE);
	// Office Open XML formats accepted beside PDF, with the part every genuine file must contain.
	private static final Map<String, String> OFFICE_MAIN_PARTS = Map.of(
			"docx", "word/document.xml",
			"xlsx", "xl/workbook.xml",
			"pptx", "ppt/presentation.xml");
	private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");
	private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
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
	private final MeetingRepository meetingRepository;

	public StoredDocumentResponse storeForUser(UUID userId, String kindValue, MultipartFile file) {
		return storeForUser(userId, kindValue, file, null);
	}

	/** With a meetingId the file is attached to that meeting, which must be the uploader's startup's. */
	public StoredDocumentResponse storeForUser(UUID userId, String kindValue, MultipartFile file, UUID meetingId) {
		if (userId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		if (DocumentKind.PEER_EVIDENCE.value().equals(kindValue)) {
			// Only the peer-mentoring profile stores these, with who may see them.
			throw new InvalidRequestException("Peer mentor evidence is uploaded from the peer mentor profile.");
		}
		AppUser user = appUserRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
		boolean avatar = DocumentKind.AVATAR.value().equals(kindValue);
		// A profile photo belongs to the person, so it is kept off the startup's file list.
		UUID organizationId = avatar || user.getOrganization() == null ? null : user.getOrganization().getId();
		if (meetingId != null) {
			Meeting meeting = meetingRepository.findById(meetingId)
					.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
			if (organizationId == null || !organizationId.equals(meeting.getOrganization().getId())) {
				throw new InvalidRequestException("The meeting belongs to another startup.");
			}
		}
		try {
			StoredDocumentResponse stored = store(
					user.getId(),
					organizationId,
					kindValue,
					file.getOriginalFilename(),
					file.getContentType(),
					file.getBytes(),
					meetingId);
			if (avatar) {
				removeOlderAvatars(user.getId());
			}
			return stored;
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

	// Only the newest photo is ever shown, so the rest are removed. Keeping the newest,
	// rather than this request's own upload, means two uploads racing each other agree
	// on what stays; a failed cleanup is left for the next upload to finish.
	private void removeOlderAvatars(UUID userId) {
		List<StoredDocument> avatars = storedDocumentRepository
				.findByUserIdAndKind(userId, DocumentKind.AVATAR.value());
		StoredDocument newest = avatars.stream()
				.max(Comparator.comparing(StoredDocument::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(StoredDocument::getId))
				.orElse(null);
		List<StoredDocument> older = avatars.stream()
				.filter(document -> document != newest)
				.toList();
		if (older.isEmpty()) {
			return;
		}
		try {
			storedDocumentRepository.deleteAll(older);
		} catch (RuntimeException error) {
			log.warn("Stored a new profile photo for {} but could not remove the older ones: {}",
					userId, error.getMessage());
			return;
		}
		for (StoredDocument document : older) {
			try {
				objectStorage.delete(document.getS3Key());
			} catch (RuntimeException error) {
				log.warn("Replaced profile photo {} but its stored object {} remains: {}",
						document.getId(), document.getS3Key(), error.getMessage());
			}
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

	/** Another person's CV reads as missing, so its id reveals nothing. */
	public byte[] readOwnCv(UUID userId, UUID documentId) {
		if (userId == null) {
			throw new InvalidRequestException("A signed-in user is required.");
		}
		StoredDocument document = storedDocumentRepository.findById(documentId)
				.filter(found -> userId.equals(found.getUserId()) && DocumentKind.CV.value().equals(found.getKind()))
				.orElseThrow(() -> new ResourceNotFoundException("CV not found: " + documentId));
		return objectStorage.get(document.getS3Key());
	}

	public StoredDocumentResponse store(
			UUID userId,
			UUID organizationId,
			String kindValue,
			String originalFilename,
			String contentType,
			byte[] bytes) {
		return store(userId, organizationId, kindValue, originalFilename, contentType, bytes, null);
	}

	private StoredDocumentResponse store(
			UUID userId,
			UUID organizationId,
			String kindValue,
			String originalFilename,
			String contentType,
			byte[] bytes,
			UUID meetingId) {
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
		if (kind.imageOnly() && !IMAGE_EXTENSIONS.contains(extension)) {
			throw new InvalidRequestException("Please upload a PNG, JPG, or WebP image.");
		}
		if (kind.pdfOnly() && !extension.equals("pdf")) {
			throw rejectUpload(userId, organizationId, kindValue, filename, fileSize, "extension-not-pdf", "Please upload a PDF.");
		}
		if (kind.imageOnly() && !IMAGE_EXTENSIONS.contains(extension)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"extension-not-image",
					"Please upload a PNG, JPG or WebP image.");
		}
		if (!kind.imageOnly() && !extension.equals("pdf") && !OFFICE_MAIN_PARTS.containsKey(extension)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"extension-not-allowed",
					"Please upload a PDF, DOCX or XLSX file.");
		}
		String resolvedType = resolveContentType(contentType, extension);
		if (kind.imageOnly() && !IMAGE_TYPES.contains(resolvedType)) {
			throw new InvalidRequestException("Please upload a PNG, JPG, or WebP image.");
		}
		if (kind.pdfOnly() && !PDF_TYPES.contains(resolvedType)) {
			throw rejectUpload(userId, organizationId, kindValue, filename, fileSize, "mime-not-pdf", "Please upload a PDF.");
		}
		if (kind.imageOnly() && !IMAGE_TYPES.contains(resolvedType)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"mime-not-image",
					"Please upload a PNG, JPG or WebP image.");
		}
		if (!kind.pdfOnly() && !kind.imageOnly() && !DOC_TYPES.contains(resolvedType)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					fileSize,
					"mime-not-allowed",
					"Please upload a PDF, DOCX or XLSX file.");
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
		document.setMeetingId(meetingId);
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
		return switch (extension) {
			case "docx" -> DOCX_TYPE;
			case "xlsx" -> XLSX_TYPE;
			case "pptx" -> PPTX_TYPE;
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "webp" -> "image/webp";
			default -> "application/pdf";
		};
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

		if (IMAGE_EXTENSIONS.contains(extension)) {
			if (!hasImageSignature(bytes)) {
				throw rejectUpload(
						userId,
						organizationId,
						kindValue,
						filename,
						(long) bytes.length,
						"invalid-image-structure",
						"The uploaded image is invalid.");
			}
			return;
		}

		String officeMainPart = OFFICE_MAIN_PARTS.get(extension);
		if (officeMainPart != null && !isSafeOfficeFile(bytes, officeMainPart)) {
			throw rejectUpload(
					userId,
					organizationId,
					kindValue,
					filename,
					(long) bytes.length,
					"invalid-or-unsafe-" + extension,
					"The uploaded " + extension.toUpperCase(Locale.ROOT) + " file is invalid.");
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

	private static boolean isSafeOfficeFile(byte[] bytes, String mainPart) {
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
				if (mainPart.equals(lower)) {
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

	private boolean hasImageSignature(byte[] bytes) {
		if (bytes == null) {
			return false;
		}
		if (startsWith(bytes, new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A })) {
			return true;
		}
		if (startsWith(bytes, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF })) {
			return true;
		}
		return bytes.length >= 12
				&& startsWith(bytes, new byte[] { 'R', 'I', 'F', 'F' })
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
	}

	private boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (bytes[i] != prefix[i]) {
				return false;
			}
		}
		return true;
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
