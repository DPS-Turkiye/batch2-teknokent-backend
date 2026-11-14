package com.dps.teknokent.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorMatchingMethod;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.domain.ProcessingConsentSource;
import com.dps.teknokent.domain.ProfileAnalysisStatus;
import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ConfirmProfileAnalysisRequest;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.dps.teknokent.util.JsonUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
	@Mock
	private DataProcessingConsentService consents;

	private static final ConsentAnswer CONSENT = new ConsentAnswer(true, "cv-parsing-2026-11-13");
	private static final ConsentAnswer REFUSED = new ConsentAnswer(false, null);

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
				UUID.randomUUID(), null, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(documentService, nativeProfileParserService, profileAnalysisRepository);
	}

	@Test
	void aSwitchedOffPreviewNeverReachesTheParser() {
		switchOffProfileParsing();

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void aSwitchedOffStoredCvPreviewNeverReadsTheCv() {
		switchOffProfileParsing();

		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				UUID.randomUUID(), UUID.randomUUID(), ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto"))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(documentService, nativeProfileParserService);
	}

	private static MockMultipartFile cvPdf() {
		return new MockMultipartFile(
				"file",
				"cv.pdf",
				"application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(UTF_8));
	}

	private void stubParser(String json) {
		JsonNode parsed = jsonUtils.readTree(json);
		when(nativeProfileParserService.parse(any(), eq(ProfileType.INDIVIDUAL_MENTEE), eq(OcrMode.AUTO)))
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

		profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", cvPdf());

		verifyNoInteractions(profileAnalysisRepository, documentService, organizationRepository, appUserRepository);
	}

	@Test
	void parsePreviewReturnsTheStructuredProfile() {
		stubParser("{\"current_role\":\"Ürün Yöneticisi\",\"professional_functions\":[\"Ürün\",\"Analitik\"]}");

		Map<String, Object> profile = profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT,
				"auto",
				cvPdf());

		assertThat(profile).containsEntry("current_role", "Ürün Yöneticisi");
		assertThat(profile.get("professional_functions"))
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
				.containsExactly("Ürün", "Analitik");
	}

	@Test
	void parsePreviewRequiresConsent() {
		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, REFUSED, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parsePreviewRejectsANonPdf() {
		MockMultipartFile notPdf = new MockMultipartFile("file", "cv.docx", "application/msword", "x".getBytes(UTF_8));

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", notPdf))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parsePreviewReadsAMentorsCv() {
		JsonNode parsed = jsonUtils.readTree("{\"current_company\":\"Looply\"}");
		when(nativeProfileParserService.parse(any(), eq(ProfileType.MENTOR), eq(OcrMode.AUTO)))
				.thenReturn(new NativeProfileParserService.ParseResult(
						parsed, objectMapper.createObjectNode(), objectMapper.createObjectNode()));
		when(technologyInferenceService.enrich(any())).thenAnswer(call -> call.getArgument(0));

		assertThat(profileAnalysisService.parsePreview(ProfileType.MENTOR, CONSENT, "auto", cvPdf()))
				.containsEntry("current_company", "Looply");
	}

	// A saved analysis feeds startup matching, which only understands mentee profiles.
	@Test
	void parseStillRefusesAMentorProfile() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				null, null, ProfileType.MENTOR, CONSENT, "auto", cvPdf()))
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
				caller, documentId, ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto");

		assertThat(profile).containsEntry("current_role", "Ürün Yöneticisi");
		verifyNoInteractions(profileAnalysisRepository, organizationRepository, appUserRepository);
	}

	@Test
	void parseStoredCvPreviewRejectsACvThatIsNotAPdf() {
		UUID caller = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		when(documentService.readOwnCv(caller, documentId)).thenReturn("PKword".getBytes(UTF_8));

		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				caller, documentId, ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PDF");
		verifyNoInteractions(nativeProfileParserService);
	}

	@Test
	void parseStoredCvPreviewRequiresConsentBeforeReadingTheFile() {
		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				UUID.randomUUID(), UUID.randomUUID(), ProfileType.INDIVIDUAL_MENTEE, REFUSED, "auto"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
		verifyNoInteractions(documentService, nativeProfileParserService);
	}

	@Test
	void parsePreviewRejectsAnUnknownOcrMode() {
		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "sometimes", cvPdf()))
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
		analysis.setProfileType(ProfileType.MENTEE);
		analysis.setParsedProfile("{}");
		analysis.setConfirmedProfile("{\"startup_name\":\"Acme\"}");
		analysis.setStatus(ProfileAnalysisStatus.CONFIRMED);
		analysis.setCreatedAt(Instant.now());
		when(profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, ProfileType.MENTEE, ProfileAnalysisStatus.CONFIRMED))
				.thenReturn(Optional.of(analysis));

		var response = profileAnalysisService.findLatestConfirmedStartup(organizationId);

		assertThat(response.profile()).contains("Acme");
		assertThat(response.status()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
		verify(profileAnalysisRepository)
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						organizationId, ProfileType.MENTEE, ProfileAnalysisStatus.CONFIRMED);
	}

	@Test
	void parseRequiresExplicitConsent() {
		assertThatThrownBy(() -> profileAnalysisService.parse(
				UUID.randomUUID(), null, ProfileType.MENTEE, REFUSED, "auto", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent");
	}

	@Test
	void parseStoresThePdfBeforeParsing() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		JsonNode profile = objectMapper.readTree("{\"startup_name\":\"Acme\"}");
		JsonNode empty = objectMapper.readTree("{}");
		when(nativeProfileParserService.parse(any(), eq(ProfileType.MENTEE), eq(OcrMode.AUTO)))
				.thenReturn(new NativeProfileParserService.ParseResult(profile, empty, empty));
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		MockMultipartFile file = new MockMultipartFile(
				"file", "pitch.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n(Acme)\nendobj\n%%EOF".getBytes(UTF_8));
		profileAnalysisService.parse(organizationId, null, ProfileType.MENTEE, CONSENT, "auto", file);

		verify(documentService).store(
				eq(null),
				eq(organizationId),
				eq(DocumentKind.PROFILE_PDF),
				eq("pitch.pdf"),
				eq("application/pdf"),
				eq(file.getBytes()));
		verify(nativeProfileParserService).parse(eq(file.getBytes()), eq(ProfileType.MENTEE), eq(OcrMode.AUTO));
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
						ProfileType.MENTEE,
						objectMapper.readTree("{\"startup_name\":\"Manual AI\",\"startup_summary\":\"Test\"}")));

		assertThat(response.status()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
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
						ProfileType.MENTEE,
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
						ProfileType.MENTEE,
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
		orgOwnAnalysis.setProfileType(ProfileType.MENTEE);
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
		analysis.setProfileType(ProfileType.MENTEE);
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

	@Test
	void aPreviewRecordsTheConsentBeforeTheCvGoesToTheParser() {
		stubParser("{\"current_role\":\"Ürün Yöneticisi\"}");

		profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", cvPdf());

		var order = inOrder(consents, nativeProfileParserService);
		order.verify(consents).record(CONSENT, ProcessingConsentSource.PREVIEW_UPLOAD, null);
		order.verify(nativeProfileParserService).parse(any(), eq(ProfileType.INDIVIDUAL_MENTEE), eq(OcrMode.AUTO));
	}

	@Test
	void aStoredCvPreviewRecordsTheConsentForTheCaller() throws Exception {
		UUID caller = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		when(documentService.readOwnCv(caller, documentId)).thenReturn(cvPdf().getBytes());
		stubParser("{\"current_role\":\"Ürün Yöneticisi\"}");

		profileAnalysisService.parseStoredCvPreview(caller, documentId, ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto");

		var order = inOrder(consents, nativeProfileParserService);
		order.verify(consents).record(CONSENT, ProcessingConsentSource.PREVIEW_STORED_CV, caller);
		order.verify(nativeProfileParserService).parse(any(), eq(ProfileType.INDIVIDUAL_MENTEE), eq(OcrMode.AUTO));
	}

	@Test
	void aSavedAnalysisRecordsTheConsentForThePersonTheProfileIsAbout() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		JsonNode empty = objectMapper.readTree("{}");
		when(nativeProfileParserService.parse(any(), eq(ProfileType.MENTEE), eq(OcrMode.AUTO)))
				.thenReturn(new NativeProfileParserService.ParseResult(
						objectMapper.readTree("{\"startup_name\":\"Acme\"}"), empty, empty));
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		profileAnalysisService.parse(organizationId, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf());

		var order = inOrder(consents, nativeProfileParserService);
		order.verify(consents).record(CONSENT, ProcessingConsentSource.PROFILE_ANALYSIS, null);
		order.verify(nativeProfileParserService).parse(any(), eq(ProfileType.MENTEE), eq(OcrMode.AUTO));
	}

	@Test
	void aConsentWithoutItsTextVersionIsRefusedBeforeAnythingIsReadOrRecorded() {
		ConsentAnswer unversioned = new ConsentAnswer(true, null);

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, unversioned, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consentTextVersion");
		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				UUID.randomUUID(), UUID.randomUUID(), ProfileType.INDIVIDUAL_MENTEE, unversioned, "auto"))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(consents, documentService, nativeProfileParserService);
	}

	@Test
	void aRefusedConsentIsNeverRecorded() {
		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, REFUSED, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(consents);
	}

	// The consent names a subject, so it is only kept once that person is known to belong to the organization.
	@Test
	void noConsentIsRecordedForSomeoneOutsideTheOrganization() {
		UUID organizationId = UUID.randomUUID();
		UUID outsider = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		AppUser stranger = new AppUser();
		stranger.setId(outsider);
		when(appUserRepository.findById(outsider)).thenReturn(Optional.of(stranger));

		assertThatThrownBy(() -> profileAnalysisService.parse(
				organizationId, outsider, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
		verifyNoInteractions(consents, nativeProfileParserService, documentService);
	}

	private static final UUID SEEDED_ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private void memberOf(UUID userId, UUID organizationId) {
		AppUser user = new AppUser();
		user.setId(userId);
		if (organizationId != null) {
			Organization organization = new Organization();
			organization.setId(organizationId);
			user.setOrganization(organization);
		}
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
	}

	private void stubStartupParser(JsonNode profile, JsonNode redactionSummary, JsonNode ocrSummary) {
		when(nativeProfileParserService.parse(any(), eq(ProfileType.MENTEE), eq(OcrMode.AUTO)))
				.thenReturn(new NativeProfileParserService.ParseResult(profile, redactionSummary, ocrSummary));
	}

	private ProfileAnalysis savedAnalysis() {
		ArgumentCaptor<ProfileAnalysis> saved = ArgumentCaptor.forClass(ProfileAnalysis.class);
		verify(profileAnalysisRepository).save(saved.capture());
		return saved.getValue();
	}

	private static ProfileAnalysis storedAnalysis(UUID id, String confirmedProfile) {
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(id);
		analysis.setOrganizationId(UUID.randomUUID());
		analysis.setSourceFilename("pitch.pdf");
		analysis.setProfileType(ProfileType.MENTEE);
		analysis.setParsedProfile("{\"startup_name\":\"Taslak\"}");
		analysis.setConfirmedProfile(confirmedProfile);
		analysis.setCreatedAt(Instant.parse("2026-11-10T09:00:00Z"));
		return analysis;
	}

	// The organization comes from the account, never from the request: see parseForCaller's own comment.
	@Test
	void aBrowserUploadIsFiledUnderTheCallersOwnOrganization() throws Exception {
		UUID caller = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		memberOf(caller, organizationId);
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		JsonNode empty = objectMapper.readTree("{}");
		stubStartupParser(objectMapper.readTree("{\"startup_name\":\"Acme\"}"), empty, empty);
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		MockMultipartFile file = cvPdf();

		ProfileAnalysisResponse response = profileAnalysisService.parseForCaller(
				caller, null, ProfileType.MENTEE, CONSENT, "auto", file);

		assertThat(response.organizationId()).isEqualTo(organizationId);
		assertThat(response.appUserId()).isNull();
		verify(documentService).store(
				eq(null), eq(organizationId), eq(DocumentKind.PROFILE_PDF), eq("cv.pdf"), eq("application/pdf"),
				eq(file.getBytes()));
	}

	@Test
	void aBrowserUploadFromAnAccountWithoutAnOrganizationIsRefused() {
		UUID caller = UUID.randomUUID();
		memberOf(caller, null);

		assertThatThrownBy(() -> profileAnalysisService.parseForCaller(
				caller, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("no organization");
		verifyNoInteractions(consents, documentService, nativeProfileParserService, profileAnalysisRepository,
				organizationRepository);
	}

	@Test
	void aBrowserUploadFromAnAccountThatDoesNotExistIsNotFound() {
		UUID caller = UUID.randomUUID();
		when(appUserRepository.findById(caller)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileAnalysisService.parseForCaller(
				caller, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(caller.toString());
		verifyNoInteractions(documentService, nativeProfileParserService, profileAnalysisRepository);
	}

	// A founder may upload for a team member, but only one of their own team.
	@Test
	void aBrowserUploadForSomeoneInAnotherOrganizationIsRefused() {
		UUID caller = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		UUID stranger = UUID.randomUUID();
		memberOf(caller, organizationId);
		memberOf(stranger, UUID.randomUUID());
		when(organizationRepository.existsById(organizationId)).thenReturn(true);

		assertThatThrownBy(() -> profileAnalysisService.parseForCaller(
				caller, stranger, ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not a member");
		verifyNoInteractions(consents, documentService, nativeProfileParserService, profileAnalysisRepository);
	}

	@Test
	void parseRefusesAnOrganizationThatDoesNotExistBeforeAnythingIsStored() {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(false);

		assertThatThrownBy(() -> profileAnalysisService.parse(
				organizationId, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Organization not found.");
		verifyNoInteractions(consents, documentService, nativeProfileParserService, profileAnalysisRepository);
	}

	@Test
	void parseForATeamMemberSavesTheEnrichedProfileWithTheParsersSummaries() throws Exception {
		UUID organizationId = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		memberOf(member, organizationId);
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		stubStartupParser(
				objectMapper.readTree("{\"startup_name\":\"Acme\",\"technologies\":[]}"),
				objectMapper.readTree("{\"mode\":\"local-before-ai\",\"total_redacted\":2}"),
				objectMapper.readTree("{\"mode\":\"auto\",\"used\":true,\"ocr_pages\":[2]}"));
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> {
			ObjectNode profile = invocation.getArgument(0);
			profile.withArray("technologies").add("Spring Boot");
			return profile;
		});
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ProfileAnalysisResponse response = profileAnalysisService.parse(
				organizationId, member, ProfileType.MENTEE, CONSENT, "auto", cvPdf());

		ProfileAnalysis saved = savedAnalysis();
		assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
		assertThat(saved.getAppUserId()).isEqualTo(member);
		assertThat(saved.getSourceFilename()).isEqualTo("cv.pdf");
		assertThat(saved.getProfileType()).isEqualTo(ProfileType.MENTEE);
		assertThat(saved.getStatus()).isEqualTo(ProfileAnalysisStatus.DRAFT);
		assertThat(saved.getConfirmedProfile()).isNull();
		assertThat(saved.getConsentAcceptedAt()).isNotNull();
		assertThat(objectMapper.readTree(saved.getParsedProfile()))
				.isEqualTo(objectMapper.readTree("{\"startup_name\":\"Acme\",\"technologies\":[\"Spring Boot\"]}"));
		assertThat(objectMapper.readTree(saved.getRedactionSummary()).path("total_redacted").asInt()).isEqualTo(2);
		assertThat(objectMapper.readTree(saved.getOcrSummary()).path("ocr_pages").path(0).asInt()).isEqualTo(2);
		assertThat(response.id()).isEqualTo(saved.getId());
		assertThat(response.profile()).contains("Spring Boot");
		verify(consents).record(CONSENT, ProcessingConsentSource.PROFILE_ANALYSIS, member);
	}

	@Test
	void summariesTheParserLeftOutAreSavedAsEmptyObjects() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		stubStartupParser(objectMapper.readTree("{\"startup_name\":\"Acme\"}"), objectMapper.nullNode(),
				objectMapper.readTree("[]"));
		when(technologyInferenceService.enrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		profileAnalysisService.parse(organizationId, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf());

		ProfileAnalysis saved = savedAnalysis();
		assertThat(saved.getRedactionSummary()).isEqualTo("{}");
		assertThat(saved.getOcrSummary()).isEqualTo("{}");
	}

	@Test
	void aParserAnswerThatIsNotAProfileObjectIsRefusedAndNoAnalysisIsSaved() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		JsonNode empty = objectMapper.readTree("{}");
		stubStartupParser(objectMapper.readTree("[\"Acme\"]"), empty, empty);

		assertThatThrownBy(() -> profileAnalysisService.parse(
				organizationId, null, ProfileType.MENTEE, CONSENT, "auto", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("usable profile");
		verifyNoInteractions(technologyInferenceService, profileAnalysisRepository);
	}

	@Test
	void aPreviewTheParserCouldNotStructureIsRefused() throws Exception {
		JsonNode empty = objectMapper.readTree("{}");
		when(nativeProfileParserService.parse(any(), eq(ProfileType.INDIVIDUAL_MENTEE), eq(OcrMode.OFF)))
				.thenReturn(
						new NativeProfileParserService.ParseResult(objectMapper.readTree("\"metin\""), empty, empty));

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "off", cvPdf()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("usable profile");
		verifyNoInteractions(technologyInferenceService);
	}

	@Test
	void anUploadWithoutAFileOrWithAnEmptyOneIsRefused() {
		MockMultipartFile emptyFile = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[0]);

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Please upload a PDF");
		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", emptyFile))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Please upload a PDF");
		verifyNoInteractions(consents, nativeProfileParserService);
	}

	@Test
	void aPdfIsAcceptedWhateverTheCaseOfItsExtension() {
		stubParser("{\"current_role\":\"Ürün Yöneticisi\"}");
		MockMultipartFile shouting = new MockMultipartFile("file", "OZGECMIS.PDF", "application/pdf",
				"%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(UTF_8));

		assertThat(profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", shouting))
				.containsEntry("current_role", "Ürün Yöneticisi");
	}

	@Test
	void anUploadOverTheSizeLimitIsRefusedWithoutReadingIt() throws Exception {
		MultipartFile huge = mock(MultipartFile.class);
		when(huge.isEmpty()).thenReturn(false);
		when(huge.getOriginalFilename()).thenReturn("pitch.pdf");
		when(huge.getSize()).thenReturn(DocumentKind.PROFILE_PDF.maxBytes() + 1);

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", huge))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded PDF is too large.");
		verify(huge, never()).getBytes();
		verifyNoInteractions(consents, nativeProfileParserService);
	}

	@Test
	void aFileThatOnlyHasAPdfNameIsRefused() {
		MockMultipartFile wordFile = new MockMultipartFile("file", "cv.pdf", "application/pdf",
				"PK word/document.xml %%EOF".getBytes(UTF_8));
		MockMultipartFile tooShort = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PD".getBytes(UTF_8));
		MockMultipartFile cutOff = new MockMultipartFile("file", "cv.pdf", "application/pdf",
				"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".getBytes(UTF_8));

		for (MockMultipartFile file : List.of(wordFile, tooShort, cutOff)) {
			assertThatThrownBy(
					() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", file))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessage("The uploaded PDF is invalid.");
		}
		verifyNoInteractions(consents, nativeProfileParserService);
	}

	@Test
	void anUploadThatCannotBeReadIsRefused() throws Exception {
		MultipartFile broken = mock(MultipartFile.class);
		when(broken.isEmpty()).thenReturn(false);
		when(broken.getOriginalFilename()).thenReturn("cv.pdf");
		when(broken.getSize()).thenReturn(1024L);
		when(broken.getBytes()).thenThrow(new IOException("temp file is gone"));

		assertThatThrownBy(
				() -> profileAnalysisService.parsePreview(ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto", broken))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The uploaded PDF could not be read.");
		verifyNoInteractions(consents, nativeProfileParserService);
	}

	@Test
	void aStoredCvThatIsCutOffBeforeItsEndIsRefused() {
		UUID caller = UUID.randomUUID();
		UUID documentId = UUID.randomUUID();
		when(documentService.readOwnCv(caller, documentId)).thenReturn("%PDF-1.4\n1 0 obj\n<<>>".getBytes(UTF_8));

		assertThatThrownBy(() -> profileAnalysisService.parseStoredCvPreview(
				caller, documentId, ProfileType.INDIVIDUAL_MENTEE, CONSENT, "auto"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only a PDF CV");
		verifyNoInteractions(consents, nativeProfileParserService);
	}

	@Test
	void manualCreateRefusesAnOrganizationThatDoesNotExist() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(false);
		CreateManualProfileRequest request = new CreateManualProfileRequest(
				ProfileType.MENTEE, objectMapper.readTree("{\"startup_name\":\"Manual AI\"}"));

		assertThatThrownBy(() -> profileAnalysisService.createManual(organizationId, null, request))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Organization not found.");
		verifyNoInteractions(profileAnalysisRepository);
	}

	@Test
	void manualCreateRefusesAProfileTypeMatchingCannotUse() throws Exception {
		CreateManualProfileRequest request = new CreateManualProfileRequest(
				ProfileType.MENTOR, objectMapper.readTree("{\"professional_summary\":\"20 yıl ürün\"}"));

		assertThatThrownBy(() -> profileAnalysisService.createManual(UUID.randomUUID(), null, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Profile type must be");
		verifyNoInteractions(organizationRepository, profileAnalysisRepository);
	}

	@Test
	void manualCreateRefusesAProfileThatIsNotAnObject() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		CreateManualProfileRequest request = new CreateManualProfileRequest(
				ProfileType.MENTEE, objectMapper.readTree("[\"Manual AI\"]"));

		assertThatThrownBy(() -> profileAnalysisService.createManual(organizationId, null, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A profile object is required.");
		verifyNoInteractions(profileAnalysisRepository);
	}

	@Test
	void aManualStartupProfileNeedsAtLeastItsNameOrSummary() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		// What an individual would fill in does not make a startup profile.
		CreateManualProfileRequest request = new CreateManualProfileRequest(
				ProfileType.MENTEE,
				objectMapper.readTree("{\"startup_name\":\" \",\"current_role\":\"Kurucu\",\"stage\":\"MVP\"}"));

		assertThatThrownBy(() -> profileAnalysisService.createManual(organizationId, null, request))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Complete at least");
		verifyNoInteractions(profileAnalysisRepository);
	}

	@Test
	void aManualIndividualProfileNeedsARoleOrSummaryNotStartupFields() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(organizationRepository.existsById(organizationId)).thenReturn(true);
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		CreateManualProfileRequest startupFieldsOnly = new CreateManualProfileRequest(
				ProfileType.INDIVIDUAL_MENTEE,
				objectMapper.readTree("{\"startup_name\":\"Acme\",\"startup_summary\":\"B2B\"}"));
		CreateManualProfileRequest withDesiredRole = new CreateManualProfileRequest(
				ProfileType.INDIVIDUAL_MENTEE, objectMapper.readTree("{\"desired_role\":\"Ürün Yöneticisi\"}"));

		assertThatThrownBy(() -> profileAnalysisService.createManual(organizationId, null, startupFieldsOnly))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Complete at least");
		ProfileAnalysisResponse created = profileAnalysisService.createManual(organizationId, null, withDesiredRole);

		assertThat(created.profileType()).isEqualTo(ProfileType.INDIVIDUAL_MENTEE);
		assertThat(created.status()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
		assertThat(created.profile()).contains("Ürün Yöneticisi");
	}

	@Test
	void withoutAnOrganizationTheSeededOneIsUsed() throws Exception {
		when(organizationRepository.existsById(SEEDED_ORGANIZATION_ID)).thenReturn(true);
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						SEEDED_ORGANIZATION_ID, ProfileType.MENTEE, ProfileAnalysisStatus.CONFIRMED))
				.thenReturn(Optional.empty());
		when(profileAnalysisRepository.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(
				SEEDED_ORGANIZATION_ID)).thenReturn(Optional.empty());

		ProfileAnalysisResponse created = profileAnalysisService.createManual(null, null,
				new CreateManualProfileRequest(ProfileType.MENTEE,
						objectMapper.readTree("{\"startup_summary\":\"B2B SaaS\"}")));

		assertThat(created.organizationId()).isEqualTo(SEEDED_ORGANIZATION_ID);
		assertThatThrownBy(() -> profileAnalysisService.findLatestConfirmedStartup(null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("No confirmed startup profile was found.");
		assertThatThrownBy(() -> profileAnalysisService.findLatest(null, null))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("No parsed startup profile was found.");
	}

	@Test
	void findLatestSaysSoWhenTheMemberHasNoParsedProfile() {
		UUID organizationId = UUID.randomUUID();
		UUID member = UUID.randomUUID();
		memberOf(member, organizationId);
		when(profileAnalysisRepository.findFirstByAppUserIdOrderByCreatedAtDesc(member)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileAnalysisService.findLatest(organizationId, member))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("No parsed profile was found.");
	}

	@Test
	void findLatestForAUserThatDoesNotExistIsNotFound() {
		UUID ghost = UUID.randomUUID();
		when(appUserRepository.findById(ghost)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileAnalysisService.findLatest(UUID.randomUUID(), ghost))
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessageContaining(ghost.toString());
		verifyNoInteractions(profileAnalysisRepository);
	}

	@Test
	void recommendationsNeedTheProfileToBeConfirmedFirst() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, null);
		UUID founder = approvedFounderOf(analysis.getOrganizationId());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertThatThrownBy(() -> profileAnalysisService.recommendations(founder, id))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("confirm the profile");
		verifyNoInteractions(mentorRecommendationService);
	}

	@Test
	void recommendationsForAnAnalysisThatDoesNotExistAreNotFound() {
		UUID id = UUID.randomUUID();
		UUID founder = approvedFounderOf(UUID.randomUUID());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileAnalysisService.recommendations(founder, id))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(mentorRecommendationService);
	}

	@Test
	void recommendationsForAConfirmedStartupProfileAreTheMentorsTheMatcherRanked() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id,
				"{\"support_needs\":[\"Fiyatlandırma\"],\"startup_summary\":\"B2B SaaS\",\"sectors\":[\"FinTech\"],"
						+ "\"stage\":\"MVP\",\"technologies\":[\"Java\",\" \"]}");
		UUID founder = approvedFounderOf(analysis.getOrganizationId());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		MentorRecommendationResult ranked = new MentorRecommendationResult(MentorMatchingMethod.PGVECTOR_SEMANTIC,
				"openai:text-embedding-3-small", Instant.parse("2026-11-14T10:00:00Z"), List.of());
		ArgumentCaptor<RecommendMentorsRequest> asked = ArgumentCaptor.forClass(RecommendMentorsRequest.class);
		when(mentorRecommendationService.recommend(asked.capture())).thenReturn(ranked);

		assertThat(profileAnalysisService.recommendations(founder, id)).isSameAs(ranked);
		RecommendMentorsRequest request = asked.getValue();
		assertThat(request.supportArea()).isEqualTo("Fiyatlandırma");
		assertThat(request.summary()).isEqualTo("B2B SaaS");
		assertThat(request.startupSector()).isEqualTo("FinTech");
		assertThat(request.startupStage()).isEqualTo("MVP");
		assertThat(request.startupTechnologies()).containsExactly("Java");
		assertThat(request.urgency()).isEqualTo(MentorshipRequestUrgency.NEXT_TWO_WEEKS);
		assertThat(request.mentorshipFormat()).isEqualTo(MentorshipRequestFormat.SINGLE_MEETING);
		assertThat(request.meetingMode()).isEqualTo(MeetingMode.ONLINE);
	}

	// Before, any signed-in caller could read the matches for, or rewrite, any analysis by its id.
	@Test
	void anotherStartupsMemberCanNeitherReadRecommendationsForNorConfirmAnAnalysis() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, "{\"startup_summary\":\"B2B SaaS\"}");
		UUID outsider = approvedFounderOf(UUID.randomUUID());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		ConfirmProfileAnalysisRequest rewrite = confirmation("{\"startup_name\":\"Ele geçirildi\"}");

		assertForbidden(() -> profileAnalysisService.recommendations(outsider, id));
		assertForbidden(() -> profileAnalysisService.confirm(outsider, id, rewrite));
		assertThat(analysis.getConfirmedProfile()).doesNotContain("Ele geçirildi");
		verifyNoInteractions(mentorRecommendationService);
		verify(profileAnalysisRepository, never()).save(any());
	}

	@Test
	void aTeamMatesPersonalAnalysisStaysTheirs() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, "{\"professional_summary\":\"Ürün\"}");
		analysis.setAppUserId(UUID.randomUUID());
		UUID teamMate = approvedFounderOf(analysis.getOrganizationId());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertForbidden(() -> profileAnalysisService.recommendations(teamMate, id));
		assertForbidden(() -> profileAnalysisService.confirm(teamMate, id, confirmation("{}")));
	}

	@Test
	void aMemberWhoIsNotApprovedOrNotAMenteeIsRefused() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, "{\"startup_summary\":\"B2B SaaS\"}");
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		UUID pending = member(analysis.getOrganizationId(), UserRole.MENTEE, UserAccountStatus.PENDING);
		UUID mentor = member(analysis.getOrganizationId(), UserRole.MENTOR, UserAccountStatus.APPROVED);

		assertForbidden(() -> profileAnalysisService.recommendations(pending, id));
		assertForbidden(() -> profileAnalysisService.recommendations(mentor, id));
		verifyNoInteractions(mentorRecommendationService);
	}

	@Test
	void theOwnerOfAPersonalAnalysisMayConfirmIt() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, null);
		UUID owner = approvedFounderOf(analysis.getOrganizationId());
		analysis.setAppUserId(owner);
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ProfileAnalysisResponse response = profileAnalysisService.confirm(owner, id,
				confirmation("{\"desired_role\":\"Ürün Yöneticisi\"}"));

		assertThat(response.status()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
		assertThat(response.profile()).contains("Ürün Yöneticisi");
	}

	@Test
	void confirmKeepsTheReviewedProfileAndMarksTheAnalysisConfirmed() throws Exception {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, null);
		UUID founder = approvedFounderOf(analysis.getOrganizationId());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));
		when(profileAnalysisRepository.save(any(ProfileAnalysis.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ProfileAnalysisResponse response = profileAnalysisService.confirm(founder,
				id, confirmation("{\"startup_name\":\"Acme\",\"stage\":\"MVP\"}"));

		ProfileAnalysis saved = savedAnalysis();
		assertThat(saved).isSameAs(analysis);
		assertThat(objectMapper.readTree(saved.getConfirmedProfile()))
				.isEqualTo(objectMapper.readTree("{\"startup_name\":\"Acme\",\"stage\":\"MVP\"}"));
		assertThat(saved.getStatus()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
		assertThat(saved.getConfirmedAt()).isNotNull();
		assertThat(saved.getParsedProfile()).as("what the parser read stays as it was").contains("Taslak");
		assertThat(response.status()).isEqualTo(ProfileAnalysisStatus.CONFIRMED);
		assertThat(response.profile()).contains("Acme").doesNotContain("Taslak");
	}

	@Test
	void confirmNeedsAProfileObjectAndSavesNothingWithoutOne() {
		UUID id = UUID.randomUUID();
		ProfileAnalysis analysis = storedAnalysis(id, null);
		UUID founder = approvedFounderOf(analysis.getOrganizationId());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.of(analysis));

		assertThatThrownBy(() -> profileAnalysisService.confirm(founder, id, confirmation("\"Acme\"")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A profile object is required.");
		assertThatThrownBy(() -> profileAnalysisService.confirm(founder, id, confirmation("null")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A profile object is required.");
		assertThat(analysis.getStatus()).isEqualTo(ProfileAnalysisStatus.DRAFT);
		verify(profileAnalysisRepository, never()).save(any());
	}

	@Test
	void confirmingAnAnalysisThatDoesNotExistIsNotFound() {
		UUID id = UUID.randomUUID();
		UUID founder = approvedFounderOf(UUID.randomUUID());
		when(profileAnalysisRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> profileAnalysisService.confirm(founder, id, confirmation("{}")))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(profileAnalysisRepository, never()).save(any());
	}

	private UUID approvedFounderOf(UUID organizationId) {
		return member(organizationId, UserRole.MENTEE, UserAccountStatus.APPROVED);
	}

	private UUID member(UUID organizationId, UserRole role, UserAccountStatus status) {
		UUID userId = UUID.randomUUID();
		AppUser user = new AppUser();
		user.setId(userId);
		user.setRole(role);
		user.setStatus(status);
		Organization organization = new Organization();
		organization.setId(organizationId);
		user.setOrganization(organization);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
		return userId;
	}

	private ConfirmProfileAnalysisRequest confirmation(String profileJson) {
		return new ConfirmProfileAnalysisRequest(objectMapper.readTree(profileJson));
	}

	private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}
}
