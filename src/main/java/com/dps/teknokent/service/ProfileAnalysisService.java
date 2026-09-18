package com.dps.teknokent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dps.teknokent.util.JsonUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileAnalysisService {

	private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final byte[] PDF_MAGIC = new byte[] { '%', 'P', 'D', 'F', '-' };
	private static final int PDF_EOF_SCAN_BYTES = 4 * 1024;

	private final ProfileAnalysisRepository profileAnalysisRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;
	private final TechnologyInferenceService technologyInferenceService;
	private final NativeProfileParserService nativeProfileParserService;
	private final MentorRecommendationService mentorRecommendationService;
	private final DocumentService documentService;
	private final JsonUtils jsonUtils;

	/**
	 * Same as {@link #parse} but with the organization taken from the signed-in
	 * account rather than a request parameter.
	 *
	 * <p>The browser now posts here directly, because the frontend's own edge
	 * blocks bodies over ~8KB on its proxy routes. That turns {@code
	 * organizationId} from a value the proxy injected out of the session cookie
	 * into one the caller sets freely, and {@link #parse} falls back to a real
	 * seeded organization when it is absent — so a signed-in user could file a CV
	 * under someone else's startup and feed it into their matching. Resolving it
	 * from the principal closes that. {@code appUserId} stays a parameter because
	 * a founder may upload for a team member, and requireMemberOfOrganization
	 * already confines that to their own organization.
	 */
	public ProfileAnalysisResponse parseForCaller(
			UUID callerUserId, UUID appUserId, String profileType, boolean consentAccepted, String ocrMode,
			MultipartFile file) {
		AppUser caller = appUserRepository.findById(callerUserId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerUserId));
		if (caller.getOrganization() == null) {
			throw new InvalidRequestException("This account has no organization to file a profile under.");
		}
		return parse(caller.getOrganization().getId(), appUserId, profileType, consentAccepted, ocrMode, file);
	}

	public ProfileAnalysisResponse parse(
			UUID organizationId, UUID appUserId, String profileType, boolean consentAccepted, String ocrMode,
			MultipartFile file) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(profileType);
		validateOcrMode(ocrMode);
		requireConsent(consentAccepted);
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
		requireMemberOfOrganization(appUserId, resolvedOrganizationId);
		byte[] pdfBytes = readPdf(file);
		documentService.store(
				appUserId,
				resolvedOrganizationId,
				"profile-pdf",
				file.getOriginalFilename(),
				file.getContentType(),
				pdfBytes);

		NativeProfileParserService.ParseResult parseResult = nativeProfileParserService.parse(
				pdfBytes, profileType, ocrMode);
		JsonNode profile = parseResult.profile();
		if (!profile.isObject()) throw new InvalidRequestException("The parser did not return a usable profile.");
		profile = technologyInferenceService.enrich(((ObjectNode) profile).deepCopy());

		JsonNode redactionSummary = parseResult.redactionSummary();
		JsonNode ocrSummary = parseResult.ocrSummary();
		ProfileAnalysis analysis = ProfileAnalysis.fromParsedProfile(
				resolvedOrganizationId, appUserId, file.getOriginalFilename(), profileType, jsonUtils.writeValue(profile),
				redactionSummary.isObject() ? jsonUtils.writeValue(redactionSummary) : "{}",
				ocrSummary.isObject() ? jsonUtils.writeValue(ocrSummary) : "{}", Instant.now());
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	/**
	 * Parses a CV and returns the structured profile <strong>without persisting
	 * anything</strong> — no stored document, no profile_analysis row.
	 *
	 * <p>That is the point rather than an optimisation. {@link #parse} scopes both
	 * writes to an organization and falls back to {@code DEFAULT_ORGANIZATION_ID},
	 * which is a real seeded organization: calling it during signup would file a
	 * stranger's CV under someone else's startup and feed it into matching. A
	 * signing-up founder has no organization yet, and neither does a mentor, so
	 * the only safe answer is to persist nothing and let the caller decide what
	 * to keep. The CV itself is uploaded separately through POST /api/documents.
	 */
	public Map<String, Object> parsePreview(String profileType, boolean consentAccepted, String ocrMode, MultipartFile file) {
		validatePreviewProfileType(profileType);
		validateOcrMode(ocrMode);
		requireConsent(consentAccepted);
		return preview(readPdf(file), profileType, ocrMode);
	}

	/**
	 * {@link #parsePreview} for a CV the caller already uploaded, so someone who
	 * skipped parsing at signup can fill their profile from it later without
	 * sending the file again. Persists nothing, like the upload version.
	 */
	public Map<String, Object> parseStoredCvPreview(
			UUID callerUserId, UUID documentId, String profileType, boolean consentAccepted, String ocrMode) {
		validatePreviewProfileType(profileType);
		validateOcrMode(ocrMode);
		requireConsent(consentAccepted);
		byte[] bytes = documentService.readOwnCv(callerUserId, documentId);
		if (!hasPdfSignature(bytes) || !hasPdfEofMarker(bytes)) {
			throw new InvalidRequestException("Only a PDF CV can be read into the profile.");
		}
		return preview(bytes, profileType, ocrMode);
	}

	private Map<String, Object> preview(byte[] pdfBytes, String profileType, String ocrMode) {
		NativeProfileParserService.ParseResult parseResult = nativeProfileParserService.parse(
				pdfBytes, profileType, ocrMode);
		JsonNode profile = parseResult.profile();
		if (!profile.isObject()) {
			throw new InvalidRequestException("The parser did not return a usable profile.");
		}
		return jsonUtils.toMap(technologyInferenceService.enrich(((ObjectNode) profile).deepCopy()));
	}

	private void validateOcrMode(String ocrMode) {
		if (!java.util.Set.of("off", "auto", "force").contains(ocrMode)) {
			throw new InvalidRequestException("OCR mode must be off, auto, or force.");
		}
	}

	private void requireConsent(boolean consentAccepted) {
		if (!consentAccepted) {
			throw new InvalidRequestException("Document processing consent is required.");
		}
	}

	private byte[] readPdf(MultipartFile file) {
		if (file == null || file.isEmpty() || file.getOriginalFilename() == null
				|| !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
			throw new InvalidRequestException("Please upload a PDF pitch deck or company profile.");
		}
		if (file.getSize() > DocumentKind.PROFILE_PDF.maxBytes()) {
			throw new InvalidRequestException("The uploaded PDF is too large.");
		}
		try {
			byte[] bytes = file.getBytes();
			if (!hasPdfSignature(bytes) || !hasPdfEofMarker(bytes)) {
				throw new InvalidRequestException("The uploaded PDF is invalid.");
			}
			return bytes;
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded PDF could not be read.");
		}
	}

	private static boolean hasPdfSignature(byte[] bytes) {
		if (bytes.length < PDF_MAGIC.length) return false;
		for (int index = 0; index < PDF_MAGIC.length; index++) {
			if (bytes[index] != PDF_MAGIC[index]) return false;
		}
		return true;
	}

	private static boolean hasPdfEofMarker(byte[] bytes) {
		int scanLength = Math.min(bytes.length, PDF_EOF_SCAN_BYTES);
		String tail = new String(
				bytes,
				bytes.length - scanLength,
				scanLength,
				java.nio.charset.StandardCharsets.ISO_8859_1);
		return tail.contains("%%EOF");
	}

	public ProfileAnalysisResponse createManual(UUID organizationId, UUID appUserId, CreateManualProfileRequest request) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(request.profileType());
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
		requireMemberOfOrganization(appUserId, resolvedOrganizationId);
		if (!request.profile().isObject()) {
			throw new InvalidRequestException("A profile object is required.");
		}
		boolean hasMinimumProfile = "individual_mentee".equals(request.profileType())
				? hasText(request.profile(), "professional_summary", "current_role", "desired_role")
				: hasText(request.profile(), "startup_name", "startup_summary");
		if (!hasMinimumProfile) {
			throw new InvalidRequestException("Complete at least the profile name, role, or summary.");
		}

		Instant now = Instant.now();
		ProfileAnalysis analysis = ProfileAnalysis.fromManualProfile(
				resolvedOrganizationId, appUserId, request, jsonUtils.writeValue(request.profile()), now);
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	public ProfileAnalysisResponse findLatest(UUID organizationId, UUID appUserId) {
		if (appUserId != null) {
			return profileAnalysisRepository.findFirstByAppUserIdOrderByCreatedAtDesc(appUserId)
					.map(ProfileAnalysisResponse::fromEntity)
					.orElseThrow(() -> new ResourceNotFoundException("No parsed profile was found."));
		}
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(resolvedOrganizationId)
				.map(ProfileAnalysisResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("No parsed startup profile was found."));
	}

	private void requireMemberOfOrganization(UUID appUserId, UUID organizationId) {
		if (appUserId == null) return;
		AppUser user = appUserRepository.findById(appUserId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + appUserId));
		if (user.getOrganization() == null || !user.getOrganization().getId().equals(organizationId)) {
			throw new InvalidRequestException("The selected user is not a member of this organization.");
		}
	}

	public ProfileAnalysisResponse findLatestConfirmedStartup(UUID organizationId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullAndProfileTypeAndStatusOrderByCreatedAtDesc(
						resolvedOrganizationId, "mentee", "CONFIRMED")
				.map(ProfileAnalysisResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("No confirmed startup profile was found."));
	}

	public JsonNode recommendations(UUID id) {
		ProfileAnalysis analysis = profileAnalysisRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Parsed startup profile not found."));
		if (analysis.getConfirmedProfile() == null) {
			throw new InvalidRequestException("Review and confirm the profile before requesting mentors.");
		}
		JsonNode profile = jsonUtils.readTree(analysis.getConfirmedProfile());
		RecommendMentorsRequest request = new RecommendMentorsRequest(
				firstText(profile, "support_needs", "skills_to_develop", "desired_role", "current_role"),
				firstText(profile, "startup_summary", "professional_summary", "career_goals", "goals"),
				"NEXT_TWO_WEEKS", "SINGLE_MEETING", "ONLINE",
				firstText(profile, "sectors", "industries"), profile.path("stage").asText(""),
				firstText(profile, "startup_summary", "professional_summary"), textList(profile.path("technologies")), null);
		return jsonUtils.valueToTree(mentorRecommendationService.recommend(request));
	}

	public ProfileAnalysisResponse confirm(UUID id, String payload) {
		ProfileAnalysis analysis = profileAnalysisRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Parsed startup profile not found."));
		JsonNode request = jsonUtils.readTree(payload);
		JsonNode profile = request.path("profile");
		if (!profile.isObject()) {
			throw new InvalidRequestException("A profile object is required.");
		}
		analysis.confirm(jsonUtils.writeValue(profile), Instant.now());
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	private String firstText(JsonNode profile, String... fields) {
		for (String field : fields) {
			JsonNode value = profile.path(field);
			if (value.isTextual() && !value.asText().isBlank()) return value.asText();
			if (value.isArray() && !value.isEmpty()) {
				JsonNode first = value.get(0);
				if (first.isTextual()) return first.asText();
				if (first.isObject()) {
					String claim = first.path("claim").asText("");
					if (!claim.isBlank()) return claim;
				}
			}
		}
		return "Genel mentorluk desteği";
	}

	private List<String> textList(JsonNode values) {
		List<String> result = new ArrayList<>();
		if (values.isArray()) values.forEach(value -> {
			if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
		});
		return result;
	}

	// A preview stores nothing and feeds no matching, so a mentor may read their own CV too.
	private void validatePreviewProfileType(String profileType) {
		if (!"mentor".equals(profileType)) {
			validateProfileType(profileType);
		}
	}

	private void validateProfileType(String profileType) {
		if (!"mentee".equals(profileType) && !"individual_mentee".equals(profileType)) {
			throw new InvalidRequestException("Profile type must be mentee or individual_mentee.");
		}
	}

	private boolean hasText(JsonNode profile, String... fields) {
		for (String field : fields) {
			if (!profile.path(field).asText("").isBlank()) return true;
		}
		return false;
	}

}
