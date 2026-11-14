package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProcessingConsentSource;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DataProcessingConsent;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.security.FirebaseTokenVerifier;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.service.NativeProfileParserService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Someone an admin deleted signs up again with the same address and reads their CV in the signup wizard. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CvPreviewAfterResignupIntegrationTest {

	private static final String OLD_UID = "uid-before-the-deletion";
	private static final String NEW_UID = "uid-after-signing-up-again";
	private static final String NEW_TOKEN = "token-after-signing-up-again";
	private static final String TEXT_VERSION = "cv-2026-11";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@PersistenceContext
	private EntityManager entityManager;

	@MockitoBean
	private LoginProvisioner loginProvisioner;
	@MockitoBean
	private FirebaseTokenVerifier firebaseTokenVerifier;
	@MockitoBean
	private NativeProfileParserService nativeProfileParserService;

	private AppUser deleted;
	private String email;

	@BeforeEach
	void anAdminDeletesTheAccountAndThePersonSignsUpAgain() throws Exception {
		AppUser admin = user(UserRole.ADMIN, UUID.randomUUID() + "@resignup.test", "uid-of-the-admin");
		email = UUID.randomUUID() + "@resignup.test";
		deleted = user(UserRole.MENTEE, email, OLD_UID);
		entityManager.flush();
		entityManager.clear();
		when(loginProvisioner.uidForEmail(anyString())).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/admin/users/{id}/delete", deleted.getId())
						.with(authentication(new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
								admin.getId(), admin.getEmail(), admin.getRole(), admin.getStatus(), "uid-of-the-admin"),
								null, List.of())))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"confirmEmail\":\"" + email + "\"}"))
				.andExpect(status().isOk());

		// Same address, proven to be theirs, on a login Firebase made anew.
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify(NEW_TOKEN))
				.thenReturn(Optional.of(new FirebaseIdentity(NEW_UID, email, true)));
		ObjectNode profile = JsonNodeFactory.instance.objectNode().put("current_role", "Founder");
		when(nativeProfileParserService.parse(any(), anyString(), anyString()))
				.thenReturn(new NativeProfileParserService.ParseResult(
						profile, JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode()));
	}

	@Test
	void theCvIsReadBeforeTheNewAccountExistsAndTheConsentNamesTheNewLogin() throws Exception {
		preview().andExpect(status().isOk()).andExpect(jsonPath("$.current_role").value("Founder"));

		DataProcessingConsent consent = onlyConsent();
		assertThat(consent.getConsenterFirebaseUid()).isEqualTo(NEW_UID);
		assertThat(consent.getConsenterUserId()).isNull();
		assertThat(consent.getSource()).isEqualTo(ProcessingConsentSource.PREVIEW_UPLOAD);
		assertTheDeletedRowIsLeftAlone();
	}

	@Test
	void theCvIsReadOnceTheNewAccountWaitsForApprovalAndTheConsentNamesThatAccount() throws Exception {
		AppUser again = user(UserRole.MENTEE, email, NEW_UID);
		again.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(again);
		entityManager.clear();

		preview().andExpect(status().isOk());

		DataProcessingConsent consent = onlyConsent();
		assertThat(consent.getConsenterUserId()).isEqualTo(again.getId()).isNotEqualTo(deleted.getId());
		assertThat(consent.getConsenterFirebaseUid()).isNull();
		assertTheDeletedRowIsLeftAlone();
	}

	private ResultActions preview() throws Exception {
		byte[] pdf = "%PDF-1.4\nA short CV\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
		return mockMvc.perform(multipart("/api/profile-analyses/preview")
				.file(new MockMultipartFile("file", "cv.pdf", MediaType.APPLICATION_PDF_VALUE, pdf))
				.param("consentAccepted", "true")
				.param("consentTextVersion", TEXT_VERSION)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + NEW_TOKEN));
	}

	private DataProcessingConsent onlyConsent() {
		entityManager.flush();
		entityManager.clear();
		List<DataProcessingConsent> consents = entityManager
				.createQuery("select c from DataProcessingConsent c", DataProcessingConsent.class)
				.getResultList();
		assertThat(consents).hasSize(1);
		return consents.getFirst();
	}

	private void assertTheDeletedRowIsLeftAlone() {
		AppUser tombstone = userRepository.findById(deleted.getId()).orElseThrow();
		assertThat(tombstone.getStatus()).isEqualTo(UserAccountStatus.DELETED);
		assertThat(tombstone.getFirebaseUid()).isNull();
		assertThat(tombstone.getEmail()).isNotEqualToIgnoringCase(email);
	}

	private AppUser user(UserRole role, String address, String firebaseUid) {
		AppUser user = new AppUser();
		user.setFullName("Deniz Kaya");
		user.setEmail(address);
		user.setRole(role);
		user.setFirebaseUid(firebaseUid);
		return userRepository.saveAndFlush(user);
	}
}
