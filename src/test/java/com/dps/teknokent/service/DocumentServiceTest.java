package com.dps.teknokent.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.dps.teknokent.domain.DocumentKind;
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
				UUID.randomUUID(), UUID.randomUUID(), DocumentKind.OTHER, "financial-model.xlsx",
				"application/octet-stream",
				xlsx);

		assertThat(response.contentType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
	}

	@Test
	void rejectsAnXlsxWithoutAWorkbook() throws Exception {
		byte[] notASpreadsheet = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "model.xlsx", "application/octet-stream", notASpreadsheet))
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
				UUID.randomUUID(), UUID.randomUUID(), DocumentKind.PITCH_DECK, "deck.pptx", "application/octet-stream",
				pptx);

		assertThat(response.contentType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
	}

	@Test
	void rejectsAPptxWithoutAPresentation() throws Exception {
		byte[] notADeck = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.PITCH_DECK, "deck.pptx", "application/octet-stream", notADeck))
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
				"file", "user flow v2.pdf", "application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(UTF_8));

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
				"file", "notes.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes(UTF_8));

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
				"file", "pitch deck.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(UTF_8));

		var response = documentService.storeForUser(userId, "pitch-deck", file, null);

		assertThat(response.kind()).isEqualTo(DocumentKind.PITCH_DECK);
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
				userId, UUID.randomUUID(), DocumentKind.LOGO, "mark.png", "image/png", png);

		assertThat(response.kind()).isEqualTo(DocumentKind.LOGO);
		verify(objectStorage).put(any(String.class), eq(png), eq("image/png"));
	}

	@Test
	void rejectsALogoWhoseBytesAreNotAnImage() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.LOGO, "mark.png", "image/png", "png".getBytes(UTF_8)))
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
				UUID.randomUUID(), null, DocumentKind.LOGO, "pitch.pdf", "application/pdf", "x".getBytes(UTF_8)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("image");
	}

	@Test
	void listsDocumentsForTheUserNewestFirst() {
		UUID userId = UUID.randomUUID();
		StoredDocument cv = new StoredDocument();
		cv.setId(UUID.randomUUID());
		cv.setUserId(userId);
		cv.setKind(DocumentKind.CV);
		cv.setOriginalFilename("cv.pdf");
		cv.setContentType("application/pdf");
		cv.setSizeBytes(10);
		cv.setCreatedAt(java.time.Instant.parse("2026-08-28T12:00:00Z"));
		when(storedDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId))
				.thenReturn(java.util.List.of(cv));

		var documents = documentService.listForUser(userId);

		assertThat(documents).hasSize(1);
		assertThat(documents.get(0).kind()).isEqualTo(DocumentKind.CV);
		assertThat(documents.get(0).originalFilename()).isEqualTo("cv.pdf");
	}

	@Test
	void leavesPeerMentorEvidenceOutOfTheGeneralList() {
		UUID userId = UUID.randomUUID();
		StoredDocument cv = storedCv(userId, DocumentKind.CV);
		StoredDocument evidence = storedCv(userId, DocumentKind.PEER_EVIDENCE);
		when(storedDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId))
				.thenReturn(java.util.List.of(evidence, cv));

		var documents = documentService.listForUser(userId);

		assertThat(documents).extracting(document -> document.kind()).containsExactly(DocumentKind.CV);
	}

	@Test
	void readsTheCallersOwnCv() {
		UUID userId = UUID.randomUUID();
		StoredDocument cv = storedCv(userId, DocumentKind.CV);
		when(storedDocumentRepository.findById(cv.getId())).thenReturn(Optional.of(cv));
		when(objectStorage.require(cv.getS3Key())).thenReturn("%PDF-".getBytes(UTF_8));

		assertThat(documentService.readOwnCv(userId, cv.getId())).isEqualTo("%PDF-".getBytes(UTF_8));
	}

	@Test
	void treatsSomeoneElsesCvAsMissing() {
		StoredDocument cv = storedCv(UUID.randomUUID(), DocumentKind.CV);
		when(storedDocumentRepository.findById(cv.getId())).thenReturn(Optional.of(cv));

		assertThatThrownBy(() -> documentService.readOwnCv(UUID.randomUUID(), cv.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(objectStorage, never()).require(any());
	}

	@Test
	void readsOnlyACvThroughTheCvReader() {
		UUID userId = UUID.randomUUID();
		StoredDocument deck = storedCv(userId, DocumentKind.PITCH_DECK);
		when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));

		assertThatThrownBy(() -> documentService.readOwnCv(userId, deck.getId()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(objectStorage, never()).require(any());
	}

	private static StoredDocument storedCv(UUID ownerId, DocumentKind kind) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(ownerId);
		document.setKind(kind);
		document.setS3Key("documents/" + ownerId + "/" + kind.value() + "/file.pdf");
		return document;
	}

	@Test
	void rejectsListingWithoutAUser() {
		assertThatThrownBy(() -> documentService.listForUser(null))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsUnknownKindsBeforeLookingAnythingUp() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> documentService.storeForUser(userId, "photo", pdfFile(), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Unknown document kind.");
		verify(appUserRepository, never()).findById(any());
		verify(storedDocumentRepository, never()).save(any());
	}

	@Test
	void rejectsProfilePdfThatIsNotPdf() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				UUID.randomUUID(),
				DocumentKind.PROFILE_PDF,
				"notes.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"x".getBytes(UTF_8)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
	}

	@Test
	void rejectsPdfWithoutEofMarker() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(),
				null,
				DocumentKind.PITCH_DECK,
				"deck.pdf",
				"application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj".getBytes(UTF_8)))
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
				DocumentKind.PITCH_DECK,
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
				DocumentKind.PITCH_DECK,
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
				DocumentKind.PITCH_DECK,
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

		var response = documentService.storeForUser(userId, "avatar", avatarFile(), null);

		assertThat(response.kind()).isEqualTo(DocumentKind.AVATAR);
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

		var response = documentService.storeForUser(userId, "avatar", avatarFile(), null);

		assertThat(response.kind()).isEqualTo(DocumentKind.AVATAR);
		verify(storedDocumentRepository).deleteAll(List.of(older));
	}

	@Test
	void keepsTheNewestProfilePhotoWhenAnotherUploadRacedAhead() {
		UUID userId = UUID.randomUUID();
		StoredDocument older = olderAvatar(userId);
		StoredDocument racedAhead = olderAvatar(userId);
		racedAhead.setCreatedAt(Instant.now().plusSeconds(60));
		AtomicReference<StoredDocument> saved = stubAvatarUpload(userId, older, racedAhead);

		documentService.storeForUser(userId, "avatar", avatarFile(), null);

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

		var response = documentService.storeForUser(userId, "avatar", avatarFile(), null);

		assertThat(response.kind()).isEqualTo(DocumentKind.AVATAR);
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
		older.setKind(DocumentKind.AVATAR);
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
		when(storedDocumentRepository.findByUserIdAndKind(userId, DocumentKind.AVATAR)).thenAnswer(invocation -> {
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
				zip.write(entries[index + 1].getBytes(UTF_8));
				zip.closeEntry();
			}
		}
		return output.toByteArray();
	}

	@Test
	void rejectsAnUploadWithoutASignedInUser() {
		assertThatThrownBy(() -> documentService.storeForUser(null, "cv", pdfFile(), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("signed-in user");
		verifyNoInteractions(appUserRepository, objectStorage, storedDocumentRepository);
	}

	@Test
	void reportsAnUploaderWhoDoesNotExist() {
		UUID userId = UUID.randomUUID();
		when(appUserRepository.findById(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentService.storeForUser(userId, "cv", pdfFile(), null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(userId.toString());
		verifyNoInteractions(objectStorage, storedDocumentRepository);
	}

	@Test
	void reportsAMeetingThatDoesNotExist() {
		UUID meetingId = UUID.randomUUID();
		AppUser user = uploader(startup());
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(meetingRepository.findById(meetingId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> documentService.storeForUser(user.getId(), "meeting-file", pdfFile(), meetingId))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(meetingId.toString());
		verifyNoInteractions(objectStorage, storedDocumentRepository);
	}

	@Test
	void storesAFileOfSomeoneWithoutAStartupUnderThePersonAlone() {
		AppUser user = uploader(null);
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = documentService.storeForUser(user.getId(), "cv", pdfFile(), null);

		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(response.organizationId()).isNull();
		assertThat(response.sizeBytes()).isEqualTo(pdfBytes().length);
	}

	@Test
	void reportsAnUploadWhoseBytesCannotBeRead() {
		AppUser user = uploader(startup());
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		MockMultipartFile unreadable = new MockMultipartFile("file", "cv.pdf", "application/pdf", pdfBytes()) {
			@Override
			public byte[] getBytes() throws IOException {
				throw new IOException("stream closed");
			}
		};

		assertThatThrownBy(() -> documentService.storeForUser(user.getId(), "cv", unreadable, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("could not be read");
		verifyNoInteractions(objectStorage, storedDocumentRepository);
	}

	@Test
	void leavesEverythingInPlaceWhenThePersonHasNoOlderProfilePhoto() {
		AppUser user = uploader(startup());
		AtomicReference<StoredDocument> saved = new AtomicReference<>();
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(storedDocumentRepository.save(any(StoredDocument.class))).thenAnswer(invocation -> {
			saved.set(invocation.getArgument(0));
			return saved.get();
		});
		when(storedDocumentRepository.findByUserIdAndKind(user.getId(), DocumentKind.AVATAR))
				.thenAnswer(invocation -> List.of(saved.get()));

		var response = documentService.storeForUser(user.getId(), "avatar", avatarFile(), null);

		assertThat(response.id()).isEqualTo(saved.get().getId());
		verify(storedDocumentRepository, never()).deleteAll(any());
		verify(objectStorage, never()).delete(any());
	}

	@Test
	void storesTheProfilePhotoEvenWhenNoPhotoIsListedAfterwards() {
		AppUser user = uploader(startup());
		when(appUserRepository.findById(user.getId())).thenReturn(Optional.of(user));
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(storedDocumentRepository.findByUserIdAndKind(user.getId(), DocumentKind.AVATAR)).thenReturn(List.of());

		var response = documentService.storeForUser(user.getId(), "avatar", avatarFile(), null);

		assertThat(response.kind()).isEqualTo(DocumentKind.AVATAR);
		verify(storedDocumentRepository, never()).deleteAll(any());
		verify(objectStorage, never()).delete(any());
	}

	@Test
	void keepsThePhotoWithTheGreaterIdWhenTwoWereStoredAtTheSameInstant() {
		UUID userId = UUID.randomUUID();
		Instant sameInstant = Instant.parse("2999-01-01T00:00:00Z");
		StoredDocument lowerId = olderAvatar(userId);
		lowerId.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
		lowerId.setCreatedAt(sameInstant);
		StoredDocument greaterId = olderAvatar(userId);
		greaterId.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
		greaterId.setCreatedAt(sameInstant);
		AtomicReference<StoredDocument> saved = stubAvatarUpload(userId, lowerId, greaterId);

		documentService.storeForUser(userId, "avatar", avatarFile(), null);

		verify(storedDocumentRepository).deleteAll(List.of(lowerId, saved.get()));
		verify(objectStorage).delete(lowerId.getS3Key());
		verify(objectStorage).delete(saved.get().getS3Key());
		verify(objectStorage, never()).delete(greaterId.getS3Key());
	}

	@Test
	void rejectsReadingACvWithoutASignedInUser() {
		assertThatThrownBy(() -> documentService.readOwnCv(null, UUID.randomUUID()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("signed-in user");
		verifyNoInteractions(storedDocumentRepository, objectStorage);
	}

	@Test
	void rejectsAnEmptyFile() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "cv.pdf", "application/pdf", new byte[0]))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A file is required.");
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "cv.pdf", "application/pdf", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A file is required.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void acceptsAFileExactlyAtTheKindsSizeLimit() {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		byte[] atTheLimit = Arrays.copyOf(pngBytes(), (int) DocumentKind.LOGO.maxBytes());

		var response = documentService.store(UUID.randomUUID(), null, DocumentKind.LOGO, "mark.png", "image/png",
				atTheLimit);

		assertThat(response.sizeBytes()).isEqualTo(DocumentKind.LOGO.maxBytes());
	}

	@Test
	void rejectsAFileOneByteOverTheKindsSizeLimit() {
		byte[] tooLarge = Arrays.copyOf(pngBytes(), (int) DocumentKind.LOGO.maxBytes() + 1);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.LOGO, "mark.png", "image/png", tooLarge))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The file is too large.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAFileWithoutAName() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, null, "application/pdf", pdfBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A file name is required.");
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "   ", "application/pdf", pdfBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A file name is required.");
		verifyNoInteractions(objectStorage);
	}

	@ParameterizedTest
	@ValueSource(strings = { "notes.txt", "cv.doc", "macro.docm", "archive", "trailing-dot.", "deck.pdf.exe" })
	void rejectsADocumentWhoseExtensionIsNeitherPdfNorOffice(String filename) {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.PITCH_DECK, filename, "application/pdf", pdfBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Please upload a PDF, DOCX or XLSX file.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAnImageSentWithADocumentContentType() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.LOGO, "mark.png", "application/pdf", pngBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("image");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAPdfOnlyKindSentWithAnotherContentType() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.PROFILE_PDF, "profile.pdf", "text/html", pdfBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Please upload a PDF.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsADocumentSentWithAContentTypeThatIsNotAllowed() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.PITCH_DECK, "deck.pdf", "text/html", pdfBytes()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Please upload a PDF, DOCX or XLSX file.");
		verifyNoInteractions(objectStorage);
	}

	@ParameterizedTest
	@MethodSource("uploadsWithoutAUsableContentType")
	void fallsBackToTheExtensionWhenNoUsableContentTypeIsSent(
			DocumentKind kind, String filename, String sentType, byte[] bytes, String storedAs) {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = documentService.store(UUID.randomUUID(), null, kind, filename, sentType, bytes);

		assertThat(response.contentType()).isEqualTo(storedAs);
		verify(objectStorage).put(any(String.class), eq(bytes), eq(storedAs));
	}

	static Stream<Arguments> uploadsWithoutAUsableContentType() throws IOException {
		byte[] word = docx(
				"[Content_Types].xml", "<Types></Types>",
				"word/document.xml", "<w:document></w:document>");
		return Stream.of(
				Arguments.of(DocumentKind.PITCH_DECK, "deck.pdf", "application/octet-stream", pdfBytes(),
						"application/pdf"),
				Arguments.of(DocumentKind.PITCH_DECK, "deck.pdf", " ", pdfBytes(), "application/pdf"),
				Arguments.of(DocumentKind.OTHER, "notes.docx", null, word,
						"application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
				Arguments.of(DocumentKind.LOGO, "mark.png", null, pngBytes(), "image/png"),
				Arguments.of(DocumentKind.LOGO, "photo.jpg", "application/octet-stream", jpegBytes(), "image/jpeg"),
				Arguments.of(DocumentKind.AVATAR, "photo.JPEG", "", jpegBytes(), "image/jpeg"),
				Arguments.of(DocumentKind.LOGO, "mark.webp", null, webpBytes(), "image/webp"));
	}

	@Test
	void storesTheContentTypeInLowerCase() {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "cv.PDF", "Application/PDF", pdfBytes());

		assertThat(response.contentType()).isEqualTo("application/pdf");
	}

	@Test
	void filesAnUploadNobodyOwnsUnderItsStartup() {
		UUID organizationId = UUID.randomUUID();
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		documentService.store(null, organizationId, DocumentKind.PITCH_DECK, "deck.pdf", "application/pdf", pdfBytes());

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(objectStorage).put(key.capture(), any(byte[].class), eq("application/pdf"));
		assertThat(key.getValue()).startsWith("documents/org/" + organizationId + "/pitch-deck/");
	}

	@Test
	void keepsTheWholeFileNameOnTheRecordButOnlyASafeTailOfItInTheStorageKey() {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		String longName = "Döngü girişimi yatırımcı sunumu " + "çok uzun ".repeat(12) + "son sürüm.pdf";

		var response = documentService.store(
				UUID.randomUUID(), null, DocumentKind.PITCH_DECK, "  " + longName + " ", "application/pdf", pdfBytes());

		assertThat(response.originalFilename()).isEqualTo(longName);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(objectStorage).put(key.capture(), any(byte[].class), any(String.class));
		String storedName = key.getValue().substring(key.getValue().indexOf(response.id().toString()) + 37);
		assertThat(storedName).hasSize(80).matches("[a-zA-Z0-9._-]+").endsWith("son_s_r_m.pdf");
	}

	@Test
	void rejectsAPdfThatDoesNotStartWithThePdfSignature() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "cv.pdf", "application/pdf", "%PD".getBytes(UTF_8)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded PDF is invalid.");
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.CV, "cv.pdf", "application/pdf",
				"<html><script>alert(1)</script></html>\n%%EOF".getBytes(UTF_8)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded PDF is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void acceptsADocxWithFoldersAndEmptyParts() throws Exception {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, byte[]> parts = wordParts();
		parts.put("word/media/", new byte[0]);
		parts.put("word/media/empty.bin", new byte[0]);
		byte[] word = zip(parts);

		var response = documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", word);

		assertThat(response.originalFilename()).isEqualTo("notes.docx");
		verify(objectStorage).put(any(String.class), eq(word), any(String.class));
	}

	@Test
	void acceptsADocxWithTwoThousandEntriesButNotOneMore() throws Exception {
		when(storedDocumentRepository.save(any(StoredDocument.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, byte[]> parts = wordParts();
		for (int index = parts.size(); index < 2_000; index++) {
			parts.put("word/media/part-" + index + ".xml", new byte[0]);
		}
		byte[] atTheLimit = zip(parts);
		parts.put("word/media/one-more.xml", new byte[0]);
		byte[] overTheLimit = zip(parts);

		documentService.store(UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream",
				atTheLimit);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", overTheLimit))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verify(objectStorage, never()).put(any(String.class), eq(overTheLimit), any(String.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/etc/cron.d/job", "\\\\server\\share.xml", "word/embeddings/setup.exe",
			"word/embeddings/RUN.BAT", "word/embeddings/tools.jar", "word/vbaProject.bin" })
	void rejectsADocxWithAnEntryThatEscapesTheArchiveOrCanRun(String entryName) throws Exception {
		Map<String, byte[]> parts = wordParts();
		parts.put(entryName, "x".getBytes(UTF_8));
		byte[] unsafe = zip(parts);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", unsafe))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	// No entry is over its own limit or suspiciously compressible; only the sum is too much.
	@Test
	void rejectsAnOfficeFileThatExpandsBeyondTheTotalLimit() throws Exception {
		byte[] noisy = new byte[14 * 1024 * 1024];
		new Random(7).nextBytes(noisy);
		for (int index = 0; index < noisy.length; index++) {
			noisy[index] = (byte) ('a' + (noisy[index] & 1));
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream archive = new ZipOutputStream(output)) {
			archive.setLevel(Deflater.BEST_SPEED);
			for (Map.Entry<String, byte[]> part : wordParts().entrySet()) {
				archive.putNextEntry(new ZipEntry(part.getKey()));
				archive.write(part.getValue());
				archive.closeEntry();
			}
			for (int index = 0; index < 3; index++) {
				archive.putNextEntry(new ZipEntry("word/media/noise-" + index + ".bin"));
				archive.write(noisy);
				archive.closeEntry();
			}
		}
		byte[] expandsTooFar = output.toByteArray();
		assertThat((long) expandsTooFar.length).isLessThan(DocumentKind.OTHER.maxBytes());

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", expandsTooFar))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAnOfficeFileWithAnEntryCompressedLikeAZipBomb() throws Exception {
		Map<String, byte[]> parts = wordParts();
		parts.put("word/media/bomb.bin", "a".repeat(2 * 1024 * 1024).getBytes(UTF_8));
		byte[] bomb = zip(parts);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", bomb))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAnOfficeFileThatIsNotAnArchive() {
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "model.xlsx", "application/octet-stream",
				"name,amount\nrent,1200\n".getBytes(UTF_8)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded XLSX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsAnOfficeFileCutOffMidway() throws Exception {
		byte[] incompressible = new byte[8 * 1024];
		new Random(11).nextBytes(incompressible);
		Map<String, byte[]> parts = new LinkedHashMap<>();
		parts.put("word/media/photo.bin", incompressible);
		parts.putAll(wordParts());
		byte[] whole = zip(parts);
		byte[] cutOff = Arrays.copyOf(whole, 2 * 1024);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", cutOff))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsADocxWithoutItsContentTypesPart() throws Exception {
		byte[] incomplete = docx("word/document.xml", "<w:document></w:document>");

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.OTHER, "notes.docx", "application/octet-stream", incomplete))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded DOCX file is invalid.");
		verifyNoInteractions(objectStorage);
	}

	@Test
	void rejectsARiffFileThatIsNotAWebPImage() {
		byte[] wave = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".getBytes(UTF_8);
		byte[] headerOnly = "RIFF\u0000\u0000".getBytes(UTF_8);

		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.LOGO, "mark.webp", "image/webp", wave))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded image is invalid.");
		assertThatThrownBy(() -> documentService.store(
				UUID.randomUUID(), null, DocumentKind.LOGO, "mark.webp", "image/webp", headerOnly))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded image is invalid.");
		verifyNoInteractions(objectStorage);
	}

	private static Organization startup() {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		return organization;
	}

	private static AppUser uploader(Organization organization) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setOrganization(organization);
		return user;
	}

	private static byte[] pdfBytes() {
		return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(UTF_8);
	}

	private static MockMultipartFile pdfFile() {
		return new MockMultipartFile("file", "cv.pdf", "application/pdf", pdfBytes());
	}

	private static byte[] jpegBytes() {
		byte[] bytes = new byte[32];
		bytes[0] = (byte) 0xFF;
		bytes[1] = (byte) 0xD8;
		bytes[2] = (byte) 0xFF;
		return bytes;
	}

	private static byte[] webpBytes() {
		return "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(UTF_8);
	}

	/** The two parts every genuine .docx has; mutable so a test can add what it is about. */
	private static Map<String, byte[]> wordParts() {
		Map<String, byte[]> parts = new LinkedHashMap<>();
		parts.put("[Content_Types].xml", "<Types></Types>".getBytes(UTF_8));
		parts.put("word/document.xml", "<w:document></w:document>".getBytes(UTF_8));
		return parts;
	}

	private static byte[] zip(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream archive = new ZipOutputStream(output)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				archive.putNextEntry(new ZipEntry(entry.getKey()));
				archive.write(entry.getValue());
				archive.closeEntry();
			}
		}
		return output.toByteArray();
	}
}
