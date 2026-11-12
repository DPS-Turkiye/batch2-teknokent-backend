package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileAnalysisServiceTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	@Spy
	private JsonUtils jsonUtils = new JsonUtils(objectMapper);

	@Mock
	private ProfileAnalysisRepository profileAnalysisRepository;

	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private TechnologyInferenceService technologyInferenceService;
	@Mock
	private NativeProfileParserService nativeProfileParserService;
	@Mock
	private MentorRecommendationService mentorRecommendationService;
	@Mock
	private DocumentService documentService;

	@Spy
	private AiFeatures aiFeatures = TestAiFeatures.allOn();

	@InjectMocks
	private ProfileAnalysisService profileAnalysisService;

	private void switchOffProfileParsing() {
		ReflectionTestUtils.setField(
				profileAnalysisService, "aiFeatures", TestAiFeatures.allOnExcept(AiFeature.PROFILE_PARSING));
	}

	@Test
	void aSwitchedOffParseStoresNothingAndNeverReachesTheParser() {
		switchOffProfileParsing();

		assertThatThrownBy(() -> profileAnalysisService.parse(
				UUID.randomUUID(), null, "mentee", true, "auto", cvPdf()))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(documentService, nativeProfileParserService, profileAnalysisRepository);
	}

	@Test
	void aSwitchedOffPreviewNeverReachesTheParser() {
		switchOffProfileParsing();

		assertThatThrownBy(() -> profileAnalysisService.parsePreview("individual_mentee", true, "auto", cvPdf()))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void aSwitchedOffStoredCvPreviewNeverReadsTheCv() {
		switchOffProfileParsing();

		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				UUID.randomUUID(), UUID.randomUUID(), "individual_mentee", true, "auto"))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(documentService, nativeProfileParserService);
	}

	private static MockMultipartFile cvPdf() {
		return new MockMultipartFile(
				"file",
				"cv.pdf",
				"application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes());
	}

	private void stubParser(String json) {
		JsonNode parsed = jsonUtils.readTree(json);
		when(nativeProfileParserService.parse(any(), eq("individual_mentee"), eq("auto")))
				.thenReturn(new NativeProfileParserService.ParseResult(
						parsed, objectMapper.createObjectNode(), objectMapper.createObjectNode()));
		when(technologyInferenceService.enrich(any())).thenAnswer(call -> call.getArgument(0));
	}

	// The entire reason this method exists: parse() scopes its two writes to an
	// organization and falls back to a real seeded one, so a signup-time parse
	// would file a stranger's CV under someone else's startup.
	@Test
	void parsePreviewPersistsNothing() {
		stubParser("{\"current_role\":\"Ürün Yöneticisi\"}");

		profileAnalysisService.parsePreview("individual_mentee", true, "auto", cvPdf());

		verifyNoInteractions(profileAnalysisRepository, documentService, organizationRepository, appUserRepository);
	}

	@Test
	void parsePreviewReturnsTheStructuredProfile() {
		stubParser("{\"current_role\":\"Ürün Yöneticisi\",\"professional_functions\":[\"Ürün\",\"Analitik\"]}");

		Map<String, Object> profile = profileAnalysisService.parsePreview("individual_mentee", true, "auto", cvPdf());

		assertThat(profile).containsEntry("current_role", "Ürün Yöneticisi");
		assertThat(profile.get("professional_functions"))
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
				.containsExactly("Ürün", "Analitik");
	}

	@Test
	void parsePreviewRequiresConsent() {
		assertThatThrownBy(() -> profileAnalysisService.parsePreview("individual_mentee", false, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parsePreviewRejectsANonPdf() {
		MockMultipartFile notPdf = new MockMultipartFile("file", "cv.docx", "application/msword", "x".getBytes());

		assertThatThrownBy(() -> profileAnalysisService.parsePreview("individual_mentee", true, "auto", notPdf))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parsePreviewRejectsAnUnknownProfileType() {
		assertThatThrownBy(() -> profileAnalysisService.parsePreview("investor", true, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentee");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parsePreviewReadsAMentorsCv() {
		JsonNode parsed = jsonUtils.readTree("{\"current_company\":\"Looply\"}");
		when(nativeProfileParserService.parse(any(), eq("mentor"), eq("auto")))
				.thenReturn(new NativeProfileParserService.ParseResult(
						parsed, objectMapper.createObjectNode(), objectMapper.createObjectNode()));
		when(technologyInferenceService.enrich(any())).thenAnswer(call -> call.getArgument(0));

		assertThat(profileAnalysisService.parsePreview("mentor", true, "auto", cvPdf()))
				.containsEntry("current_company", "Looply");
	}

	// A saved analysis feeds startup matching, which only understands mentee profiles.
	@Test
	void parseStillRefusesAMentorProfile() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				null, null, "mentor", true, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(nativeProfileParserService, documentService);
	}

	@Test
	void parseStoredCvPreviewReadsTheCallersCvAndPersistsNothing() throws Exception {
		UUID caller = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		when(documentService.readOwnCv(caller, documentId)).thenReturn(cvPdf().getBytes());
		stubParser("{\"current_role\":\"Ürün Yöneticisi\"}");

		Map<String, Object> profile = profileAnalysisService.parseStoredCvPreview(
				caller, documentId, "individual_mentee", true, "auto");

		assertThat(profile).containsEntry("current_role", "Ürün Yöneticisi");
		verifyNoInteractions(profileAnalysisRepository, organizationRepository, appUserRepository);
	}

	@Test
	void parseStoredCvPreviewRejectsACvThatIsNotAPdf() {
		UUID caller = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		when(documentService.readOwnCv(caller, documentId)).thenReturn("PKword".getBytes());

		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				caller, documentId, "individual_mentee", true, "auto"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parseStoredCvPreviewRequiresConsentBeforeReadingTheFile() {
		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				UUID.randomUUID(), UUID.randomUUID(), "individual_mentee", false, "auto"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
		verifyNoInteractions(documentService, nativeProfileParserService);
	}

	@Test
	void parsePreviewRejectsAnUnknownOcrMode() {
		assertThatThrownBy(() -> profileAnalysisService.parsePreview("individual_mentee", true, "sometimes", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("OCR mode");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void latestConfirmedStartupUsesOnlyConfirmedMenteeProfiles() {
		UUID organizationId = UUID.randomUUID();
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(organizationId);
		analysis.setSourceFilename("pitch-deck.pdf");
		analysis.setProfileType("mentee");
		analysis.setParsedProfile("{}");
		analysis.setConfirmedProfile("{\"startup_name\":\"Acme\"}");
		analysis.setStatus("CONFIRMED");
		analysis.setCreatedAt(Instant.now());
		when(profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED"))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatestConfirmedStartup(organizationId);

		assertThat(response.profile()).contains("Acme");
		assertThat(response.status()).isEqualTo("CONFIRMED");
		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, "mentee", "CONFIRMED");
	}

	@Test
	void parseRequiresExplicitConsent() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				UUID.randomUUID(), null, "mentee", false, "auto", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
	}

	@Test
	void parseStoresThePdfBeforeParsing() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		JsonNode profile = objectMapper.readTree("{\"startup_name\":\"Acme\"}");
		JsonNode empty = objectMapper.readTree("{}");
		when(nativeProfileParserService.parse(any(), eq("mentee"), eq("auto")))
				.thenReturn(new NativeProfileParserService.ParseResult(profile, empty, empty));
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MockMultipartFile file = new MockMultipartFile(
				"file", "pitch.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n(Acme)\nendobj\n%%EOF".getBytes());
		profileAnalysisService.parse(organizationId, null, "mentee", true, "auto", file);

		verify(documentService).store(
				eq(null),
				eq(organizationId),
				eq("profile-pdf"),
				eq("pitch.pdf"),
				eq("application/pdf"),
				eq(file.getBytes()));
		verify(nativeProfileParserService).parse(eq(file.getBytes()), eq("mentee"), eq("auto"));
	}

	@Test
	void manualFallbackCreatesConfirmedProfile() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = profileAnalysisService.createManual(
				organizationId,
				null,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.status()).isEqualTo("CONFIRMED");
		assertThat(response.sourceFilename()).isEqualTo("manual-entry");
		assertThat(response.profile()).contains("Manual AI");
	}

	@Test
	void manualCreateRejectsUserOutsideTheOrganization() throws Exception {
		UUID organizationId = UUID.randomUUID();
		UUID otherOrganizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		Organization otherOrganization = new Organization();
		otherOrganization.setId(otherOrganizationId);
		AppUser outsider = new AppUser();
		outsider.setId(appUserId);
		outsider.setOrganization(otherOrganization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(outsider));

		assertThatThrownBy(() -> profileAnalysisService.createManual(
				organizationId,
				appUserId,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
	}

	@Test
	void manualCreateScopesToAppUserWhenProvided() throws Exception {
		UUID organizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		Organization organization = new Organization();
		organization.setId(organizationId);
		AppUser member = new AppUser();
		member.setId(appUserId);
		member.setOrganization(organization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(member));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var response = profileAnalysisService.createManual(
				organizationId,
				appUserId,
				new CreateManualProfileRequest(
						"mentee",
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.appUserId()).isEqualTo(appUserId);
	}

	@Test
	void findLatestForSelfNeverReturnsATeamMembersCv() {
		UUID organizationId = UUID.randomUUID();
		ProfileAnalysis orgOwnAnalysis = new ProfileAnalysis();
		orgOwnAnalysis.setId(UUID.randomUUID());
		orgOwnAnalysis.setOrganizationId(organizationId);
		orgOwnAnalysis.setSourceFilename("founder-pitch.pdf");
		orgOwnAnalysis.setProfileType("mentee");
		orgOwnAnalysis.setParsedProfile("{}");
		orgOwnAnalysis.setCreatedAt(Instant.now());
		when(profileAnalysisRepository.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(organizationId))
				.thenReturn(Optional.of(orgOwnAnalysis));

		profileAnalysisService.findLatest(organizationId, null);

		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(organizationId);
		verify(profileAnalysisRepository, org.mockito.Mockito.never())
				.findFirstByAppUserIdOrderByCreatedAtDesc(any());
	}

	@Test
	void findLatestScopesToAppUserWhenProvided() {
		UUID organizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(organizationId);
		AppUser member = new AppUser();
		member.setId(appUserId);
		member.setOrganization(organization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(member));
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(UUID.randomUUID());
		analysis.setAppUserId(appUserId);
		analysis.setSourceFilename("member.pdf");
		analysis.setProfileType("mentee");
		analysis.setParsedProfile("{}");
		analysis.setCreatedAt(java.time.Instant.now());
		when(profileAnalysisRepository.findFirstByAppUserIdOrderByCreatedAtDesc(appUserId))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatest(organizationId, appUserId);

		assertThat(response.appUserId()).isEqualTo(appUserId);
		verify(profileAnalysisRepository, org.mockito.Mockito.never())
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(any());
	}

	// Otherwise anyone could read another startup's parsed CV by naming its owner.
	@Test
	void findLatestRefusesAUserOutsideTheCallersOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID appUserId = UUID.randomUUID();
		Organization otherOrganization = new Organization();
		otherOrganization.setId(UUID.randomUUID());
		AppUser outsider = new AppUser();
		outsider.setId(appUserId);
		outsider.setOrganization(otherOrganization);
		when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(outsider));

		assertThatThrownBy(() -> profileAnalysisService.findLatest(organizationId, appUserId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
		verify(profileAnalysisRepository, org.mockito.Mockito.never())
				.findFirstByAppUserIdOrderByCreatedAtDesc(any());
	}
}
