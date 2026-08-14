package com.dps.teknokent.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.dps.teknokent.dto.responses.ProfileAnalysisResponse;
import com.dps.teknokent.dto.requests.CreateManualProfileRequest;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProfileAnalysisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileAnalysisService {

	private static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private final ProfileAnalysisRepository profileAnalysisRepository;
	private final OrganizationRepository organizationRepository;
	private final TechnologyInferenceService technologyInferenceService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Value("${profile-labs.parser-url:http://host.docker.internal:8765}")
	private String parserUrl;

	@Value("${profile-labs.matcher-url:http://host.docker.internal:8766}")
	private String matcherUrl;

	@Value("${OPENAI_MODEL:gpt-5.6-luna}")
	private String parserModel;

	@Value("${OPENROUTER_MODEL:openai/gpt-4o-mini}")
	private String openRouterParserModel;

	@Value("${AI_PROVIDER:openai}")
	private String aiProvider;

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

		JsonNode labResult = callParser(file, profileType, ocrMode);
		JsonNode profile = labResult.path("results").path("verified").path("profile");
		if (!profile.isObject()) {
			profile = labResult.path("results").path("direct_pdf").path("profile");
		}
		if (!profile.isObject()) {
			profile = labResult.path("results").path("local_text").path("profile");
		}
		if (!profile.isObject()) {
			String detail = labResult.path("results").path("direct_pdf").path("error")
					.asText(labResult.path("results").path("local_text").path("error")
							.asText("The parser did not return a usable profile."));
			throw new InvalidRequestException(detail);
		}
		profile = technologyInferenceService.enrich(((com.fasterxml.jackson.databind.node.ObjectNode) profile).deepCopy());

		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(resolvedOrganizationId);
		analysis.setSourceFilename(file.getOriginalFilename());
		analysis.setProfileType(profileType);
		analysis.setParsedProfile(writeJson(profile));
		analysis.setConsentAcceptedAt(Instant.now());
		JsonNode redactionSummary = labResult.path("pii_redaction");
		analysis.setRedactionSummary(redactionSummary.isObject() ? writeJson(redactionSummary) : "{}");
		JsonNode ocrSummary = labResult.path("ocr");
		analysis.setOcrSummary(ocrSummary.isObject() ? writeJson(ocrSummary) : "{}");
		analysis.setCreatedAt(Instant.now());
		return toResponse(profileAnalysisRepository.save(analysis));
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
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.setId(UUID.randomUUID());
		analysis.setOrganizationId(resolvedOrganizationId);
		analysis.setSourceFilename("manual-entry");
		analysis.setProfileType(request.profileType());
		analysis.setParsedProfile(writeJson(request.profile()));
		analysis.setConfirmedProfile(writeJson(request.profile()));
		analysis.setStatus("CONFIRMED");
		analysis.setConfirmedAt(now);
		analysis.setCreatedAt(now);
		return toResponse(profileAnalysisRepository.save(analysis));
	}

	public ProfileAnalysisResponse findLatest(UUID organizationId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository.findFirstByOrganizationIdOrderByCreatedAtDesc(resolvedOrganizationId)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("No parsed startup profile was found."));
	}

	public ProfileAnalysisResponse findLatestConfirmedStartup(UUID organizationId) {
		UUID resolvedOrganizationId = organizationId == null ? DEFAULT_ORGANIZATION_ID : organizationId;
		return profileAnalysisRepository
				.findFirstByOrganizationIdAndProfileTypeAndStatusOrderByCreatedAtDesc(
						resolvedOrganizationId, "mentee", "CONFIRMED")
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("No confirmed startup profile was found."));
	}

	public JsonNode recommendations(UUID id) {
		ProfileAnalysis analysis = profileAnalysisRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Parsed startup profile not found."));
		JsonNode library = getJson(matcherUrl + "/api/parsed-profiles");
		JsonNode mentors = library.path("mentors");
		if (!mentors.isArray() || mentors.isEmpty()) {
			throw new InvalidRequestException("No parsed mentor profiles are loaded in Mentor Match Lab yet.");
		}
		if (analysis.getConfirmedProfile() == null) {
			throw new InvalidRequestException("Review and confirm the profile before requesting mentors.");
		}
		JsonNode parsedProfile = readJson(analysis.getConfirmedProfile());
		String profileKind = "individual_mentee".equals(analysis.getProfileType()) ? "individual" : "startup";
		Map<String, Object> payload = Map.of(
				"provider", "ollama",
				"mentors", mentors,
				"mentees", java.util.List.of(Map.of(
						"mentee_id", "startup-" + analysis.getId(),
						"profile_kind", profileKind,
						"profile_version", "saved-" + analysis.getId(),
						"profile", parsedProfile)));
		return postJson(matcherUrl + "/api/match", payload);
	}

	public ProfileAnalysisResponse confirm(UUID id, String payload) {
		ProfileAnalysis analysis = profileAnalysisRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Parsed startup profile not found."));
		JsonNode request = readJson(payload);
		JsonNode profile = request.path("profile");
		if (!profile.isObject()) {
			throw new InvalidRequestException("A profile object is required.");
		}
		analysis.setConfirmedProfile(writeJson(profile));
		analysis.setStatus("CONFIRMED");
		analysis.setConfirmedAt(Instant.now());
		return toResponse(profileAnalysisRepository.save(analysis));
	}

	private JsonNode callParser(MultipartFile file, String profileType, String ocrMode) {
		try {
			Map<String, Object> payload = Map.of(
					"profile_type", profileType,
					"model", "openrouter".equalsIgnoreCase(aiProvider) ? openRouterParserModel : parserModel,
					"pdf_detail", "high",
					"order", "local-first",
					"privacy_mode", "local-redacted",
					"ocr_mode", ocrMode,
					"filename", file.getOriginalFilename(),
					"pdf_base64", Base64.getEncoder().encodeToString(file.getBytes()));
			return postJson(parserUrl + "/api/benchmark", payload);
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded PDF could not be read.");
		}
	}

	private JsonNode getJson(String url) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
	}

	private JsonNode postJson(String url, Object payload) {
		return send(HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
				.build());
	}

	private JsonNode send(HttpRequest request) {
		try {
			HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
					.send(request, HttpResponse.BodyHandlers.ofString());
			JsonNode body = readJson(response.body());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new InvalidRequestException(body.path("detail").asText("Profile lab request failed."));
			}
			return body;
		} catch (IOException error) {
			throw new InvalidRequestException("Profile Lab is not running. Start the local AI tools first.");
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new InvalidRequestException("Profile Lab request was interrupted.");
		}
	}

	private ProfileAnalysisResponse toResponse(ProfileAnalysis analysis) {
		String displayedProfile = analysis.getConfirmedProfile() == null
				? analysis.getParsedProfile()
				: analysis.getConfirmedProfile();
		return new ProfileAnalysisResponse(analysis.getId(), analysis.getOrganizationId(), analysis.getSourceFilename(),
				analysis.getProfileType(), displayedProfile, analysis.getStatus(), analysis.getConsentAcceptedAt(),
				analysis.getRedactionSummary(), analysis.getOcrSummary(), analysis.getCreatedAt());
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

	private JsonNode readJson(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JsonProcessingException error) {
			throw new IllegalStateException("Saved profile data is invalid.", error);
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException error) {
			throw new IllegalStateException("Profile data could not be prepared.", error);
		}
	}
}
