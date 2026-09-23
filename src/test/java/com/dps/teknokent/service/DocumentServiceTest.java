package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private ObjectStorage objectStorage;
	@Mock
	private MeetingRepository meetingRepository;
	@InjectMocks
	private DocumentService documentService;

	@Test
	void acceptsAnXlsxSpreadsheet() throws Exception {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		byte[] xlsx = docx(
				"[Content_Types].xml", "<Types></Types>",
				"xl/workbook.xml", "<workbook></workbook>");

		var response = documentService.store(
				UUID.randomUUID(), UUID.randomUUID(), "other", "financial-model.xlsx", "application/octet-stream", xlsx);

		assertThat(response.contentType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}

	@Test
	void rejectsAnXlsxWithoutAWorkbook() throws Exception {
		byte[] notASpreadsheet = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, "other", "model.xlsx", "application/octet-stream", notASpreadsheet))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("XLSX");
	}

	@Test
	void acceptsAPptxPitchDeck() throws Exception {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		byte[] pptx = docx(
				"[Content_Types].xml", "<Types></Types>",
				"ppt/presentation.xml", "<p:presentation></p:presentation>");

		var response = documentService.store(
				UUID.randomUUID(), UUID.randomUUID(), "pitch-deck", "deck.pptx", "application/octet-stream", pptx);

		assertThat(response.contentType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
	}

	@Test
	void rejectsAPptxWithoutAPresentation() throws Exception {
		byte[] notADeck = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, "pitch-deck", "deck.pptx", "application/octet-stream", notADeck))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PPTX");
	}

	@Test
	void attachesAnUploadToAMeetingOfTheSameStartup() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		AppUser user = new AppUser();
		user.setId(userId);
		user.setOrganization(organization);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		MockMultipartFile file = new MockMultipartFile(
				"file", "user flow v2.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes());

		documentService.storeForUser(userId, "meeting-file", file, meetingId);

		ArgumentCaptor<StoredDocument> saved = ArgumentCaptor.forClass(StoredDocument.class);
		verify(storedDocumentRepository).save(saved.capture());
		assertThat(saved.getValue().getMeetingId()).isEqualTo(meetingId);
	}

	@Test
	void refusesToAttachAnUploadToAnotherStartupsMeeting() {
		UUID userId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		Organization ownStartup = new Organization();
		ownStartup.setId(UUID.randomUUID());
		Organization otherStartup = new Organization();
		otherStartup.setId(UUID.randomUUID());
		AppUser user = new AppUser();
		user.setId(userId);
		user.setOrganization(ownStartup);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(otherStartup);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));
		MockMultipartFile file = new MockMultipartFile(
				"file", "notes.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());

		assertThatThrownBy(() -> documentService.storeForUser(userId, "meeting-file", file, meetingId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("another startup");

		verify(objectStorage, never()).put(any(String.class), any(byte[].class), any(String.class));
	}

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
	void readsTheCallersOwnCv() {
		UUID userId = UUID.randomUUID();
		StoredDocument cv = storedCv(userId, "cv");
		when(storedDocumentRepository.findById(cv.getId())).thenReturn(Optional.of(cv));
		when(objectStorage.get(cv.getS3Key())).thenReturn("%PDF-".getBytes());

		assertThat(documentService.readOwnCv(userId, cv.getId())).isEqualTo("%PDF-".getBytes());
	}

	@Test
	void treatsSomeoneElsesCvAsMissing() {
		StoredDocument cv = storedCv(UUID.randomUUID(), "cv");
		when(storedDocumentRepository.findById(cv.getId())).thenReturn(Optional.of(cv));

		assertThatThrownBy(() -> documentService.readOwnCv(UUID.randomUUID(), cv.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(objectStorage, never()).get(any());
	}

	@Test
	void readsOnlyACvThroughTheCvReader() {
		UUID userId = UUID.randomUUID();
		StoredDocument deck = storedCv(userId, "pitch-deck");
		when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));

		assertThatThrownBy(() -> documentService.readOwnCv(userId, deck.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(objectStorage, never()).get(any());
	}

	private static StoredDocument storedCv(UUID ownerId, String kind) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(ownerId);
		document.setKind(kind);
		document.setS3Key("documents/" + ownerId + "/" + kind + "/file.pdf");
		return document;
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

	@Test
	void keepsAProfilePhotoOffTheStartupAndRemovesTheOlderOne() {
		UUID userId = UUID.randomUUID();
		StoredDocument older = olderAvatar(userId);
		AtomicReference<StoredDocument> saved = stubAvatarUpload(userId, older);

		var response = documentService.storeForUser(userId, "avatar", avatarFile());

		assertThat(response.kind()).isEqualTo("avatar");
		assertThat(response.organizationId()).isNull();
		verify(storedDocumentRepository).deleteAll(List.of(older));
		verify(objectStorage).delete(older.getS3Key());
		verify(objectStorage, never()).delete(saved.get().getS3Key());
	}

	@Test
	void keepsTheNewProfilePhotoWhenTheOldFileCannotBeDeleted() {
		UUID userId = UUID.randomUUID();
		StoredDocument older = olderAvatar(userId);
		stubAvatarUpload(userId, older);
		doThrow(new IllegalStateException("storage unavailable")).when(objectStorage).delete(older.getS3Key());

		var response = documentService.storeForUser(userId, "avatar", avatarFile());

		assertThat(response.kind()).isEqualTo("avatar");
		verify(storedDocumentRepository).deleteAll(List.of(older));
	}

	@Test
	void keepsTheNewestProfilePhotoWhenAnotherUploadRacedAhead() {
		UUID userId = UUID.randomUUID();
		StoredDocument older = olderAvatar(userId);
		StoredDocument racedAhead = olderAvatar(userId);
		racedAhead.setCreatedAt(Instant.now().plusSeconds(60));
		AtomicReference<StoredDocument> saved = stubAvatarUpload(userId, older, racedAhead);

		documentService.storeForUser(userId, "avatar", avatarFile());

		verify(storedDocumentRepository).deleteAll(List.of(older, saved.get()));
		verify(objectStorage, never()).delete(racedAhead.getS3Key());
	}

	@Test
	void keepsTheNewProfilePhotoWhenTheOlderRowsCannotBeRemoved() {
		UUID userId = UUID.randomUUID();
		StoredDocument older = olderAvatar(userId);
		stubAvatarUpload(userId, older);
		doThrow(new IllegalStateException("database unavailable"))
				.when(storedDocumentRepository).deleteAll(List.of(older));

		var response = documentService.storeForUser(userId, "avatar", avatarFile());

		assertThat(response.kind()).isEqualTo("avatar");
		verify(objectStorage, never()).delete(older.getS3Key());
	}

	@Test
	void refusesToAttachAProfilePhotoToAMeeting() {
		UUID userId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		AppUser user = new AppUser();
		user.setId(userId);
		user.setOrganization(organization);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> documentService.storeForUser(userId, "avatar", avatarFile(), meetingId))
				.isInstanceOf(InvalidRequestException.class);
		verify(objectStorage, never()).put(any(String.class), any(byte[].class), any(String.class));
	}

	private StoredDocument olderAvatar(UUID userId) {
		StoredDocument older = new StoredDocument();
		older.setId(UUID.randomUUID());
		older.setUserId(userId);
		older.setKind("avatar");
		older.setS3Key("documents/" + userId + "/avatar/" + older.getId() + "-me.png");
		older.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		return older;
	}

	/** The repository then lists the older photo, this upload, and any photos stored after it. */
	private AtomicReference<StoredDocument> stubAvatarUpload(
			UUID userId, StoredDocument older, StoredDocument... storedAfter) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		AppUser user = new AppUser();
		user.setId(userId);
		user.setOrganization(organization);
		AtomicReference<StoredDocument> saved = new AtomicReference<>();
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		when(storedDocumentRepository.save(any(StoredDocument.class))).thenAnswer(invocation -> {
			saved.set(invocation.getArgument(0));
			return saved.get();
		});
		when(storedDocumentRepository.findByUserIdAndKind(userId, "avatar")).thenAnswer(invocation -> {
			List<StoredDocument> listed = new java.util.ArrayList<>(List.of(older, saved.get()));
			listed.addAll(List.of(storedAfter));
			return listed;
		});
		return saved;
	}

	private static MockMultipartFile avatarFile() {
		return new MockMultipartFile("file", "me.png", "image/png", pngBytes());
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
