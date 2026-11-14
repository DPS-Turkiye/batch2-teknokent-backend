package com.dps.teknokent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingMode;
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
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.PdfBytes;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class ProfileAnalysisService {

	private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private final ProfileAnalysisRepository profileAnalysisRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;
	private final TechnologyInferenceService technologyInferenceService;
	private final NativeProfileParserService nativeProfileParserService;
	private final MentorRecommendationService mentorRecommendationService;
	private final DocumentService documentService;
	private final JsonUtils jsonUtils;
	private final AiFeatures aiFeatures;
	private final DataProcessingConsentService consents;

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
			UUID callerUserId, UUID appUserId, ProfileType profileType, ConsentAnswer consent, String ocrMode,
			MultipartFile file) {
		AppUser caller = appUserRepository.findById(callerUserId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerUserId));
		if (caller.getOrganization() == null) {
			throw new InvalidRequestException("This account has no organization to file a profile under.");
		}
		return parse(caller.getOrganization().getId(), appUserId, profileType, consent, ocrMode, file);
	}

	public ProfileAnalysisResponse parse(
			UUID organizationId, UUID appUserId, ProfileType profileType, ConsentAnswer consent, String ocrMode,
			MultipartFile file) {
		// Before anything is stored: a refused parse must not leave the PDF behind.
		aiFeatures.require(AiFeature.PROFILE_PARSING);
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(profileType);
		OcrMode mode = parseOcrMode(ocrMode);
		consent.requireAccepted();
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
		requireMemberOfOrganization(appUserId, resolvedOrganizationId);
		byte[] pdfBytes = readPdf(file);
		consents.record(consent, ProcessingConsentSource.PROFILE_ANALYSIS, appUserId);
		documentService.store(
				appUserId,
				resolvedOrganizationId,
				DocumentKind.PROFILE_PDF,
				file.getOriginalFilename(),
				file.getContentType(),
				pdfBytes);

		NativeProfileParserService.ParseResult parseResult = nativeProfileParserService.parse(
				pdfBytes, profileType, mode);
		JsonNode profile = parseResult.profile();
		if (!profile.isObject()) {
			throw new InvalidRequestException("The parser did not return a usable profile.");
		}
		profile = technologyInferenceService.enrich(((ObjectNode) profile).deepCopy());

		JsonNode redactionSummary = parseResult.redactionSummary();
		JsonNode ocrSummary = parseResult.ocrSummary();
		ProfileAnalysis analysis = ProfileAnalysis.fromParsedProfile(
				resolvedOrganizationId, appUserId, file.getOriginalFilename(), profileType,
				jsonUtils.writeValue(profile),
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
	public Map<String, Object> parsePreview(ProfileType profileType, ConsentAnswer consent, String ocrMode,
			MultipartFile file) {
		aiFeatures.require(AiFeature.PROFILE_PARSING);
		OcrMode mode = parseOcrMode(ocrMode);
		consent.requireAccepted();
		byte[] pdfBytes = readPdf(file);
		consents.record(consent, ProcessingConsentSource.PREVIEW_UPLOAD, null);
		return preview(pdfBytes, profileType, mode);
	}

	/**
	 * {@link #parsePreview} for a CV the caller already uploaded, so someone who
	 * skipped parsing at signup can fill their profile from it later without
	 * sending the file again. Persists nothing, like the upload version.
	 */
	public Map<String, Object> parseStoredCvPreview(
			@Nullable UUID callerUserId, UUID documentId, ProfileType profileType, ConsentAnswer consent,
			String ocrMode) {
		aiFeatures.require(AiFeature.PROFILE_PARSING);
		OcrMode mode = parseOcrMode(ocrMode);
		consent.requireAccepted();
		byte[] bytes = documentService.readOwnCv(callerUserId, documentId);
		if (!PdfBytes.looksComplete(bytes)) {
			throw new InvalidRequestException("Only a PDF CV can be read into the profile.");
		}
		consents.record(consent, ProcessingConsentSource.PREVIEW_STORED_CV, callerUserId);
		return preview(bytes, profileType, mode);
	}

	private Map<String, Object> preview(byte[] pdfBytes, ProfileType profileType, OcrMode ocrMode) {
		NativeProfileParserService.ParseResult parseResult = nativeProfileParserService.parse(
				pdfBytes, profileType, ocrMode);
		JsonNode profile = parseResult.profile();
		if (!profile.isObject()) {
			throw new InvalidRequestException("The parser did not return a usable profile.");
		}
		return jsonUtils.toMap(technologyInferenceService.enrich(((ObjectNode) profile).deepCopy()));
	}

	private static OcrMode parseOcrMode(String ocrMode) {
		return OcrMode.fromWire(ocrMode)
				.orElseThrow(() -> new InvalidRequestException("OCR mode must be off, auto, or force."));
	}

	private byte[] readPdf(MultipartFile file) {
		if (file == null || file.isEmpty() || file.getOriginalFilename() == null
				|| !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
			throw new InvalidRequestException("Please upload a PDF pitch deck or company profile.");
		}
		if (file.getSize() > DocumentKind.PROFILE_PDF.maxBytes()) {
			throw new InvalidRequestException("The uploaded PDF is too large.");
		}
		try {
			byte[] bytes = file.getBytes();
			if (!PdfBytes.looksComplete(bytes)) {
				throw new InvalidRequestException("The uploaded PDF is invalid.");
			}
			return bytes;
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded PDF could not be read.");
		}
	}

	public ProfileAnalysisResponse createManual(@Nullable UUID organizationId, @Nullable UUID appUserId,
			CreateManualProfileRequest request) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(request.profileType());
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
		requireMemberOfOrganization(appUserId, resolvedOrganizationId);
		if (!request.profile().isObject()) {
			throw new InvalidRequestException("A profile object is required.");
		}
		boolean hasMinimumProfile = request.profileType() == ProfileType.INDIVIDUAL_MENTEE
				? hasText(request.profile(), "professional_summary", "current_role", "desired_role")
				: hasText(request.profile(), "startup_name", "startup_summary");
		if (!hasMinimumProfile) {
			throw new InvalidRequestException("Complete at least the profile name, role, or summary.");
		}

		Instant now = Instant.now();
		ProfileAnalysis analysis = ProfileAnalysis.fromManualProfile(
				resolvedOrganizationId, appUserId, request.profileType(), jsonUtils.writeValue(request.profile()), now);
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	public ProfileAnalysisResponse findLatest(UUID organizationId, UUID appUserId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		if (appUserId != null) {
			requireMemberOfOrganization(appUserId, resolvedOrganizationId);
			return profileAnalysisRepository.findFirstByAppUserIdOrderByCreatedAtDesc(appUserId)
					.map(ProfileAnalysisResponse::fromEntity)
					.orElseThrow(() -> new ResourceNotFoundException("No parsed profile was found."));
		}
		return profileAnalysisRepository
				.findFirstByOrganizationIdAndAppUserIdIsNullOrderByCreatedAtDesc(resolvedOrganizationId)
				.map(ProfileAnalysisResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("No parsed startup profile was found."));
	}

	private void requireMemberOfOrganization(@Nullable UUID appUserId, UUID organizationId) {
		if (appUserId == null) {
			return;
		}
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
						resolvedOrganizationId, ProfileType.MENTEE, ProfileAnalysisStatus.CONFIRMED)
				.map(ProfileAnalysisResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("No confirmed startup profile was found."));
	}

	/** Mentors for a confirmed profile, for someone allowed to use it (see {@link #requireUsableBy}). */
	public MentorRecommendationResult recommendations(UUID actorId, UUID id) {
		ProfileAnalysis analysis = requireUsableBy(actorId, id);
		if (analysis.getConfirmedProfile() == null) {
			throw new InvalidRequestException("Review and confirm the profile before requesting mentors.");
		}
		JsonNode profile = jsonUtils.readTree(analysis.getConfirmedProfile());
		RecommendMentorsRequest request = new RecommendMentorsRequest(
				firstText(profile, "support_needs", "skills_to_develop", "desired_role", "current_role"),
				firstText(profile, "startup_summary", "professional_summary", "career_goals", "goals"),
				MentorshipRequestUrgency.NEXT_TWO_WEEKS, MentorshipRequestFormat.SINGLE_MEETING, MeetingMode.ONLINE,
				firstText(profile, "sectors", "industries"), profile.path("stage").asString(""),
				firstText(profile, "startup_summary", "professional_summary"), textList(profile.path("technologies")),
				null);
		return mentorRecommendationService.recommend(request);
	}

	public ProfileAnalysisResponse confirm(UUID actorId, UUID id, ConfirmProfileAnalysisRequest request) {
		ProfileAnalysis analysis = requireUsableBy(actorId, id);
		JsonNode profile = request.profile();
		if (profile == null || !profile.isObject()) {
			throw new InvalidRequestException("A profile object is required.");
		}
		analysis.confirm(jsonUtils.writeValue(profile), Instant.now());
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	/**
	 * The same rule as the profile analysis records: an approved startup member, and only an analysis of their own
	 * startup — the startup's shared one, or a personal one that is theirs. Anything else is a 403, so an id alone
	 * no longer reads or rewrites another startup's profile.
	 */
	private ProfileAnalysis requireUsableBy(UUID actorId, UUID id) {
		AppUser actor = appUserRepository.findById(actorId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + actorId));
		ProfileAnalysis analysis = profileAnalysisRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Parsed startup profile not found."));
		boolean approvedMember = actor.has(UserRole.MENTEE) && actor.getStatus() == UserAccountStatus.APPROVED
				&& actor.getOrganization() != null
				&& actor.getOrganization().getId().equals(analysis.getOrganizationId());
		boolean sharedOrOwn = analysis.getAppUserId() == null || analysis.getAppUserId().equals(actorId);
		if (!approvedMember || !sharedOrOwn) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.profileAnalysisNotYours"));
		}
		return analysis;
	}

	private String firstText(JsonNode profile, String... fields) {
		for (String field : fields) {
			JsonNode value = profile.path(field);
			if (value.isString() && !value.asString().isBlank()) {
				return value.asString();
			}
			if (value.isArray() && !value.isEmpty()) {
				JsonNode first = value.get(0);
				if (first.isString()) {
					return first.asString();
				}
				if (first.isObject()) {
					String claim = first.path("claim").asString("");
					if (!claim.isBlank()) {
						return claim;
					}
				}
			}
		}
		return "Genel mentorluk desteği";
	}

	private List<String> textList(JsonNode values) {
		List<String> result = new ArrayList<>();
		if (values.isArray()) {
			values.forEach(value -> {
				if (value.isString() && !value.asString().isBlank()) {
					result.add(value.asString());
				}
			});
		}
		return result;
	}

	// A preview stores nothing and feeds no matching, so any type may be previewed, a mentor's CV too; only the
	// startup and personal profiles are stored and matched.
	private void validateProfileType(ProfileType profileType) {
		if (profileType != ProfileType.MENTEE && profileType != ProfileType.INDIVIDUAL_MENTEE) {
			throw new InvalidRequestException("Profile type must be mentee or individual_mentee.");
		}
	}

	private boolean hasText(JsonNode profile, String... fields) {
		for (String field : fields) {
			if (!profile.path(field).asString("").isBlank()) {
				return true;
			}
		}
		return false;
	}

}
