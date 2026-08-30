package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

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
				"file", "pitch deck.pdf", "application/pdf", "%PDF-1.4 test".getBytes());

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
}
