package com.dps.teknokent.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.requests.RecommendMentorsRequest;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorRecommendationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorRecommendationService {
	private static final Set<String> MATCH_STOP_WORDS = Set.of(
			"bir", "bu", "icin", "için", "ile", "ve", "veya", "talep", "destek", "istiyoruz",
			"konusunda", "alanında", "startup", "girişim");
	private static final Map<String, String> MATCH_CONCEPTS = Map.ofEntries(
			Map.entry("kullanıcı", "user"), Map.entry("araştırma", "research"),
			Map.entry("araştırması", "research"), Map.entry("sağlık", "health"),
			Map.entry("teknoloji", "technology"), Map.entry("teknolojileri", "technology"),
			Map.entry("yazılım", "software"), Map.entry("yapay", "ai"), Map.entry("zeka", "ai"),
			Map.entry("fiyatlandırma", "pricing"), Map.entry("yatırım", "investment"),
			Map.entry("fonlama", "fundraising"), Map.entry("finansal", "financial"),
			Map.entry("hukuk", "legal"), Map.entry("veri", "data"), Map.entry("gizlilik", "privacy"),
			Map.entry("gizliliği", "privacy"), Map.entry("fikri", "intellectual"),
			Map.entry("mülkiyet", "property"), Map.entry("hibe", "grants"),
			Map.entry("iklim", "climate"), Map.entry("sürdürülebilirlik", "sustainability"),
			Map.entry("üretim", "manufacturing"), Map.entry("tedarik", "supply"),
			Map.entry("zinciri", "chain"));

	private final MentorProfileService mentorProfileService;
	private final PgVectorMentorRetrievalService pgVectorRetrievalService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Value("${profile-labs.matcher-url:http://host.docker.internal:8766}")
	private String matcherUrl;

	@Value("${AI_PROVIDER:openrouter}")
	private String provider;

	@Value("${OPENROUTER_EMBEDDING_MODEL:openai/text-embedding-3-small}")
	private String openRouterModel;

	@Value("${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}")
	private String openAiModel;

	public MentorRecommendationResult recommend(RecommendMentorsRequest request) {
		List<MentorProfileResponse> mentors = request.excludedMentorId() == null
				? mentorProfileService.findAll()
				: mentorProfileService.findAllExcept(request.excludedMentorId());
		if (mentors.isEmpty()) {
			return new MentorRecommendationResult("DETERMINISTIC", null, Instant.now(), List.of());
		}
		List<MentorProfileResponse> candidates = mentors;
		boolean vectorRetrieved = false;
		try {
			candidates = pgVectorRetrievalService.shortlist(
					queryText(request), mentors, normalizedProvider(), embeddingModel(), 15);
			vectorRetrieved = true;
		} catch (Exception ignored) {
			// Keep recommendations available while pgvector or the embedding service is offline.
		}
		try {
			return new MentorRecommendationResult(
					vectorRetrieved ? "PGVECTOR_SEMANTIC" : "SEMANTIC",
					normalizedProvider() + ":" + embeddingModel(), Instant.now(), semanticRank(request, candidates));
		} catch (Exception ignored) {
			return new MentorRecommendationResult(
					"DETERMINISTIC", "keyword-overlap-v1", Instant.now(), deterministicRank(request, mentors));
		}
	}

	private String queryText(RecommendMentorsRequest request) {
		return String.join("\n",
				"Destek alanı: " + request.supportArea(),
				"Talep özeti: " + request.summary(),
				"Sektör: " + value(request.startupSector()),
				"Aşama: " + value(request.startupStage()),
				"Girişim: " + value(request.startupSummary()),
				"Teknolojiler: " + String.join(", ",
						request.startupTechnologies() == null ? List.of() : request.startupTechnologies()));
	}

	private List<MentorProfileResponse> semanticRank(
			RecommendMentorsRequest request, List<MentorProfileResponse> mentors) throws Exception {
		List<Map<String, Object>> mentorRecords = mentors.stream().map(mentor -> Map.<String, Object>of(
				"mentor_id", mentor.id().toString(),
				"profile_version", mentor.recommendationEvidence().stream()
						.map(MentorEvidenceResponse::profileVersion).findFirst().orElse("app-profile-v1"),
				"profile", Map.of(
						"industries", values(mentor.sectorExperience()),
						"professional_functions", mentor.specialties(),
						"technologies", mentor.specialties(),
						"startup_stage_experience", values(mentor.startupStages()),
						"mentoring_areas", mentor.specialties(),
						"employment_highlights", matcherEvidence(mentor))))
				.toList();

		Map<String, Object> menteeProfile = new LinkedHashMap<>();
		menteeProfile.put("stage", request.startupStage());
		menteeProfile.put("sectors", values(request.startupSector()));
		menteeProfile.put("technologies",
				request.startupTechnologies() == null ? List.of() : request.startupTechnologies());
		menteeProfile.put("current_bottlenecks", List.of(evidence(request.summary())));
		menteeProfile.put("goals", List.of(evidence(value(request.startupSummary()) + " " + request.summary())));
		menteeProfile.put("support_needs", List.of(evidence(request.supportArea())));

		Map<String, Object> matchingPreferences = Map.of(
				"meeting_format", value(request.mentorshipFormat()) + " / " + value(request.meetingMode()),
				"urgency", value(request.urgency()));

		Map<String, Object> payload = Map.of(
				"provider", normalizedProvider(),
				"model", embeddingModel(),
				"mentors", mentorRecords,
				"mentees", List.of(Map.of(
						"mentee_id", "request-draft",
						"profile_version", "request-draft-v1",
						"profile", menteeProfile,
						"matching_preferences", matchingPreferences)));

		HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(matcherUrl + "/api/match"))
				.timeout(Duration.ofSeconds(45))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("Matcher returned " + response.statusCode());
		}
		JsonNode recommendations = objectMapper.readTree(response.body()).path("results").path(0).path("recommendations");
		Map<UUID, MentorProfileResponse> byId = mentors.stream()
				.collect(Collectors.toMap(MentorProfileResponse::id, mentor -> mentor));
		List<MentorProfileResponse> ranked = new ArrayList<>();
		for (JsonNode recommendation : recommendations) {
			MentorProfileResponse mentor = byId.get(UUID.fromString(recommendation.path("mentor_id").asText()));
			if (mentor != null) {
				ranked.add(withReasons(request, mentor, semanticReasons(request, mentor, recommendation)));
			}
			if (ranked.size() == 5) break;
		}
		if (ranked.isEmpty()) throw new IllegalStateException("Matcher returned no usable mentors");
		return ranked;
	}

	private List<MentorProfileResponse> deterministicRank(
			RecommendMentorsRequest request, List<MentorProfileResponse> mentors) {
		Set<String> query = meaningfulTokens(request.supportArea() + " " + request.summary() + " "
				+ value(request.startupSector()) + " " + value(request.startupSummary()) + " "
				+ String.join(" ", request.startupTechnologies() == null ? List.of() : request.startupTechnologies()));
		return mentors.stream()
				.sorted(Comparator.<MentorProfileResponse>comparingInt(mentor -> overlap(query, mentor)).reversed()
						.thenComparing(MentorProfileResponse::fullName))
				.limit(5)
				.map(mentor -> withReasons(request, mentor, fallbackReasons(request, mentor)))
				.toList();
	}

	private int overlap(Set<String> query, MentorProfileResponse mentor) {
		String confirmedEvidence = mentor.recommendationEvidence().stream()
				.map(item -> item.category() + " " + item.claim() + " " + item.quote())
				.collect(Collectors.joining(" "));
		Set<String> mentorTokens = meaningfulTokens(String.join(" ", mentor.specialties()) + " " + mentor.sectorExperience()
				+ " " + mentor.professionalTitle() + " " + confirmedEvidence);
		return (int) query.stream().filter(mentorTokens::contains).count();
	}

	private List<String> semanticReasons(
			RecommendMentorsRequest request, MentorProfileResponse mentor, JsonNode recommendation) {
		List<String> reasons = new ArrayList<>(requestSpecificReasons(request, mentor));
		JsonNode shared = recommendation.path("shared_fields");
		shared.fields().forEachRemaining(field -> {
			if (field.getValue().isArray() && !field.getValue().isEmpty()) {
				reasons.add(sharedFieldReason(field.getKey(), field.getValue().get(0).asText()));
			}
		});
		if (reasons.isEmpty()) reasons.add(request.supportArea() + " talebiyle semantik uyum");
		return reasons.stream().distinct().limit(3).toList();
	}

	private List<String> fallbackReasons(RecommendMentorsRequest request, MentorProfileResponse mentor) {
		List<String> reasons = requestSpecificReasons(request, mentor);
		if (reasons.isEmpty()) reasons.add(request.supportArea() + " talebiyle uzmanlık yakınlığı");
		return reasons.stream().limit(3).toList();
	}

	private List<String> requestSpecificReasons(RecommendMentorsRequest request, MentorProfileResponse mentor) {
		List<String> reasons = new ArrayList<>();
		Set<String> requestTokens = meaningfulTokens(request.supportArea() + " " + request.summary() + " "
				+ value(request.startupSector()) + " " + value(request.startupSummary()) + " "
				+ String.join(" ", request.startupTechnologies() == null ? List.of() : request.startupTechnologies()));

		mentor.specialties().stream()
				.filter(specialty -> overlaps(requestTokens, specialty))
				.sorted(Comparator.<String>comparingInt(specialty -> matchingTokenCount(requestTokens, specialty))
						.reversed()
						.thenComparing(String.CASE_INSENSITIVE_ORDER))
				.limit(2)
				.forEach(specialty -> reasons.add("Talebinizle örtüşen uzmanlık: " + specialty));

		if (reasons.size() < 3) {
			sectorSegments(mentor.sectorExperience()).stream()
					.filter(sector -> overlaps(requestTokens, sector))
					.findFirst()
					.ifPresent(sector -> reasons.add("İlgili sektör deneyimi: " + sector));
		}
		if (reasons.size() < 3 && hasStartupStageExperience(request, mentor)) {
			reasons.add(value(request.startupStage()) + " aşamasındaki girişimlerle deneyimli");
		}
		if (reasons.size() < 3 && isMeetingModeCompatible(request.meetingMode(), mentor.meetingMode())) {
			reasons.add(meetingModeLabel(request.meetingMode()) + " görüşme tercihinizle uyumlu");
		}
		if (reasons.size() < 3 && isAvailableForUrgency(request.urgency(), mentor.availabilityLabel())) {
			reasons.add("Talebinizin zamanlamasına uygun: " + mentor.availabilityLabel());
		}
		return reasons.stream().distinct().limit(3).toList();
	}

	private String sharedFieldReason(String field, String value) {
		return switch (field) {
			case "industry_sector" -> "İlgili sektör deneyimi: " + value;
			case "startup_stage" -> value + " aşamasındaki girişimlerle deneyimli";
			case "technologies" -> "İlgili teknoloji deneyimi: " + value;
			case "expertise_support" -> "Talebinizle örtüşen uzmanlık: " + value;
			case "languages" -> "Ortak çalışma dili: " + value;
			default -> "Talebinizle ortak nokta: " + value;
		};
	}

	private boolean overlaps(Set<String> requestTokens, String candidate) {
		return matchingTokenCount(requestTokens, candidate) > 0;
	}

	private int matchingTokenCount(Set<String> requestTokens, String candidate) {
		return (int) meaningfulTokens(candidate).stream().filter(requestTokens::contains).count();
	}

	private List<String> sectorSegments(String sectors) {
		return Arrays.stream(value(sectors).split("[,/&]"))
				.map(String::trim)
				.filter(segment -> !segment.isBlank())
				.toList();
	}

	private boolean hasStartupStageExperience(RecommendMentorsRequest request, MentorProfileResponse mentor) {
		String stage = value(request.startupStage()).trim();
		return !stage.isBlank() && mentor.startupStages().toLowerCase(Locale.ROOT)
				.contains(stage.toLowerCase(Locale.ROOT));
	}

	private boolean isMeetingModeCompatible(String requestedMode, String mentorMode) {
		String normalizedMentorMode = value(mentorMode).toLowerCase(Locale.forLanguageTag("tr"));
		return switch (value(requestedMode)) {
			case "ONLINE" -> normalizedMentorMode.contains("online");
			case "IN_PERSON" -> normalizedMentorMode.contains("yüz yüze");
			default -> false;
		};
	}

	private String meetingModeLabel(String requestedMode) {
		return "IN_PERSON".equals(requestedMode) ? "Yüz yüze" : "Online";
	}

	private boolean isAvailableForUrgency(String urgency, String availability) {
		String normalizedAvailability = value(availability).toLowerCase(Locale.forLanguageTag("tr"));
		return switch (value(urgency)) {
			case "THIS_WEEK" -> normalizedAvailability.contains("bu hafta");
			case "NEXT_TWO_WEEKS" -> normalizedAvailability.contains("bu hafta")
					|| normalizedAvailability.contains("gelecek hafta")
					|| normalizedAvailability.contains("iki hafta");
			default -> false;
		};
	}

	private MentorProfileResponse withReasons(
			RecommendMentorsRequest request, MentorProfileResponse mentor, List<String> reasons) {
		return new MentorProfileResponse(mentor.id(), mentor.fullName(), mentor.email(), mentor.initials(),
				mentor.professionalTitle(), mentor.company(), mentor.availabilityLabel(), mentor.responseTimeLabel(),
				mentor.meetingMode(), mentor.communicationPreference(), mentor.mentoringStyle(), mentor.fitLabel(),
				mentor.experienceLevel(), mentor.sectorExperience(), mentor.startupStages(), mentor.specialties(), reasons,
				selectEvidence(request, mentor.recommendationEvidence()));
	}

	private List<MentorEvidenceResponse> selectEvidence(
			RecommendMentorsRequest request, List<MentorEvidenceResponse> confirmedEvidence) {
		Set<String> requestTokens = meaningfulTokens(request.supportArea() + " " + request.summary() + " "
				+ value(request.startupSector()) + " " + value(request.startupSummary()) + " "
				+ String.join(" ", request.startupTechnologies() == null ? List.of() : request.startupTechnologies()));
		return confirmedEvidence.stream()
				.map(item -> Map.entry(item, matchingTokenCount(requestTokens,
						item.category() + " " + item.claim() + " " + item.quote())))
				.filter(entry -> entry.getValue() > 0)
				.sorted(Map.Entry.<MentorEvidenceResponse, Integer>comparingByValue().reversed()
						.thenComparing(entry -> entry.getKey().claim(), String.CASE_INSENSITIVE_ORDER))
				.limit(2)
				.map(Map.Entry::getKey)
				.toList();
	}

	private List<Map<String, Object>> matcherEvidence(MentorProfileResponse mentor) {
		if (mentor.recommendationEvidence().isEmpty()) {
			return List.of(evidence(mentor.professionalTitle() + " at " + mentor.company()));
		}
		return mentor.recommendationEvidence().stream().map(item -> {
			Map<String, Object> evidence = new LinkedHashMap<>();
			evidence.put("claim", item.claim());
			evidence.put("page", item.sourcePage() == null ? 1 : item.sourcePage());
			evidence.put("quote", item.quote());
			evidence.put("information_type", "explicit");
			return evidence;
		}).toList();
	}

	private Map<String, Object> evidence(String claim) {
		return Map.of("claim", value(claim), "page", 1, "quote", "request input", "information_type", "explicit");
	}

	private List<String> values(String input) {
		return input == null || input.isBlank() ? List.of() : List.of(input);
	}

	private String value(String input) {
		return input == null ? "" : input;
	}

	private String normalizedProvider() {
		return Set.of("openai", "openrouter", "ollama").contains(provider.toLowerCase(Locale.ROOT))
				? provider.toLowerCase(Locale.ROOT) : "openrouter";
	}

	private String embeddingModel() {
		return "openai".equals(normalizedProvider()) ? openAiModel
				: "ollama".equals(normalizedProvider()) ? "bge-m3" : openRouterModel;
	}

	private Set<String> tokens(String text) {
		return Arrays.stream(value(text).toLowerCase(Locale.forLanguageTag("tr"))
				.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split("\\s+"))
				.filter(token -> !token.isBlank())
				.collect(Collectors.toSet());
	}

	private Set<String> meaningfulTokens(String text) {
		return tokens(text).stream()
				.filter(token -> token.length() >= 3)
				.filter(token -> !MATCH_STOP_WORDS.contains(token))
				.map(token -> MATCH_CONCEPTS.getOrDefault(token, token))
				.collect(Collectors.toSet());
	}
}
