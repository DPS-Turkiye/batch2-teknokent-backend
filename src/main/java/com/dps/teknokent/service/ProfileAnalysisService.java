package com.dps.teknokent.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.dps.teknokent.util.JsonUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileAnalysisService {

	private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private final ProfileAnalysisRepository profileAnalysisRepository;
	private final OrganizationRepository organizationRepository;
	private final TechnologyInferenceService technologyInferenceService;
	private final NativeProfileParserService nativeProfileParserService;
	private final MentorRecommendationService mentorRecommendationService;
	private final JsonUtils jsonUtils;

	public ProfileAnalysisResponse parse(
			UUID organizationId, String profileType, boolean consentAccepted, String ocrMode, MultipartFile file) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(profileType);
		if (!java.util.Set.of("off", "auto", "force").contains(ocrMode)) {
			throw new InvalidRequestException("OCR mode must be off, auto, or force.");
		}
		if (!consentAccepted) {
			throw new InvalidRequestException("Document processing consent is required.");
		}
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
		if (file == null || file.isEmpty() || file.getOriginalFilename() == null
				|| !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
			throw new InvalidRequestException("Please upload a PDF pitch deck or company profile.");
		}

		NativeProfileParserService.ParseResult parseResult;
		try {
			parseResult = nativeProfileParserService.parse(file.getBytes(), profileType, ocrMode);
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded PDF could not be read.");
		}
		JsonNode profile = parseResult.profile();
		if (!profile.isObject()) throw new InvalidRequestException("The parser did not return a usable profile.");
		profile = technologyInferenceService.enrich(((com.fasterxml.jackson.databind.node.ObjectNode) profile).deepCopy());

		JsonNode redactionSummary = parseResult.redactionSummary();
		JsonNode ocrSummary = parseResult.ocrSummary();
		ProfileAnalysis analysis = ProfileAnalysis.fromParsedProfile(
				resolvedOrganizationId, file.getOriginalFilename(), profileType, jsonUtils.writeValue(profile),
				redactionSummary.isObject() ? jsonUtils.writeValue(redactionSummary) : "{}",
				ocrSummary.isObject() ? jsonUtils.writeValue(ocrSummary) : "{}", Instant.now());
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	public ProfileAnalysisResponse createManual(UUID organizationId, CreateManualProfileRequest request) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		validateProfileType(request.profileType());
		if (!organizationRepository.existsById(resolvedOrganizationId)) {
			throw new ResourceNotFoundException("Organization not found.");
		}
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
				resolvedOrganizationId, request, jsonUtils.writeValue(request.profile()), now);
		return ProfileAnalysisResponse.fromEntity(profileAnalysisRepository.save(analysis));
	}

	public ProfileAnalysisResponse findLatest(UUID organizationId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository.findFirstByOrganizationIdOrderByCreatedAtDesc(resolvedOrganizationId)
				.map(ProfileAnalysisResponse::fromEntity)
				.orElseThrow(() -> new ResourceNotFoundException("No parsed startup profile was found."));
	}

	public ProfileAnalysisResponse findLatestConfirmedStartup(UUID organizationId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository
				.findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
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
