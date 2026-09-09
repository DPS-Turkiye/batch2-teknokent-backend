package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private ObjectStorage objectStorage;
	@InjectMocks
	private DocumentService documentService;

	@Test
	void storesAPdfUnderTheUserAndKind() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(organizationId);
		AppUser user = new AppUser();
		user.setId(userId);
		user.setOrganization(organization);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MockMultipartFile file = new MockMultipartFile(
				"file", "pitch deck.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes());

		var response = documentService.storeForUser(userId, "pitch-deck", file);

		assertThat(response.kind()).isEqualTo("pitch-deck");
		assertThat(response.originalFilename()).isEqualTo("pitch deck.pdf");
		assertThat(response.userId()).isEqualTo(userId);
		assertThat(response.organizationId()).isEqualTo(organizationId);

		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		verify(objectStorage).put(keyCaptor.capture(), eq(file.getBytes()), eq("application/pdf"));
		assertThat(keyCaptor.getValue()).startsWith("documents/" + userId + "/pitch-deck/");
		assertThat(keyCaptor.getValue()).contains("pitch_deck.pdf");
	}

	@Test
	void storesAStartupLogoAsAnImage() {
		UUID userId = UUID.randomUUID();
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		byte[] png = pngBytes();
		var response = documentService.store(
				userId, UUID.randomUUID(), "logo", "mark.png", "image/png", png);

		assertThat(response.kind()).isEqualTo("logo");
		verify(objectStorage).put(any(String.class), eq(png), eq("image/png"));
	}

	@Test
	void rejectsALogoWhoseBytesAreNotAnImage() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, "logo", "mark.png", "image/png", "png".getBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("image");
	}

	private static byte[] pngBytes() {
		byte[] header = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		byte[] bytes = new byte[32];
		System.arraycopy(header, 0, bytes, 0, header.length);
		return bytes;
	}

	@Test
	void rejectsADocumentSubmittedAsAStartupLogo() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, "logo", "pitch.pdf", "application/pdf", "x".getBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("image");
	}

	@Test
	void listsDocumentsForTheUserNewestFirst() {
		UUID userId = UUID.randomUUID();
		StoredDocument cv = new StoredDocument();
		cv.setId(UUID.randomUUID());
		cv.setUserId(userId);
		cv.setKind("cv");
		cv.setOriginalFilename("cv.pdf");
		cv.setContentType("application/pdf");
		cv.setSizeBytes(10);
		cv.setCreatedAt(java.time.Instant.parse("2026-08-28T12:00:00Z"));
		when(storedDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId))
				.thenReturn(java.util.List.of(cv));

		var documents = documentService.listForUser(userId);

		assertThat(documents).hasSize(1);
		assertThat(documents.get(0).kind()).isEqualTo("cv");
		assertThat(documents.get(0).originalFilename()).isEqualTo("cv.pdf");
	}

	@Test
	void rejectsListingWithoutAUser() {
		assertThatThrownBy(() -> documentService.listForUser(null))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsUnknownKinds() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, "photo", "cv.pdf", "application/pdf", "x".getBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("kind");
	}

	@Test
	void rejectsProfilePdfThatIsNotPdf() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"profile-pdf",
				"notes.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"x".getBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
	}

	@Test
	void rejectsPdfWithoutEofMarker() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				null,
				"pitch-deck",
				"deck.pdf",
				"application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj".getBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("invalid");
	}

	@Test
	void rejectsDocxWithDangerousEmbeddedScript() throws Exception {
		byte[] unsafeDocx = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>",
				"word/embeddings/payload.js", "alert('x')");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				null,
				"pitch-deck",
				"deck.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				unsafeDocx))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("DOCX");
	}

	@Test
	void rejectsDocxWithPathTraversalEntry() throws Exception {
		byte[] traversalDocx = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>",
				"../escape.sh", "echo hacked");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				null,
				"pitch-deck",
				"deck.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				traversalDocx))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("DOCX");
	}

	@Test
	void rejectsDocxWhenAnEntryExpandsBeyondSafetyLimit() throws Exception {
		byte[] hugeEntryDocx = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>",
				"word/media/huge.bin", "a".repeat(21 * 1024 * 1024));

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				null,
				"pitch-deck",
				"deck.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				hugeEntryDocx))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("DOCX");
	}

	private static byte[] docx(String... entries) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			for (int index = 0; index < entries.length; index += 2) {
				zip.putNextEntry(new ZipEntry(entries[index]));
				zip.write(entries[index + 1].getBytes());
				zip.closeEntry();
			}
		}
		return output.toByteArray();
	}
}
