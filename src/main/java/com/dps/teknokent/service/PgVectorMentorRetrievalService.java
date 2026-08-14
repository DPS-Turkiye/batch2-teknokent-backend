package com.dps.teknokent.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PgVectorMentorRetrievalService {
	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Value("${profile-labs.matcher-url:http://host.docker.internal:8766}")
	private String matcherUrl;

	@Value("${matching.pgvector.enabled:true}")
	private boolean enabled;

	public List<MentorProfileResponse> shortlist(
			String queryText, List<MentorProfileResponse> mentors, String provider, String model, int limit) throws Exception {
		if (!enabled) throw new IllegalStateException("pgvector matching is disabled");
		if (mentors.isEmpty()) return mentors;

		Map<UUID, MentorProfileResponse> byId = mentors.stream()
				.collect(Collectors.toMap(MentorProfileResponse::id, Function.identity()));
		List<MentorDocument> missing = mentors.stream()
				.map(mentor -> new MentorDocument(mentor.id(), mentorText(mentor)))
				.filter(document -> !isCurrent(document, provider, model))
				.toList();
		if (!missing.isEmpty()) {
			List<List<Double>> vectors = embed(missing.stream().map(MentorDocument::text).toList(), provider, model);
			for (int index = 0; index < missing.size(); index++) {
				MentorDocument document = missing.get(index);
				upsert(document, provider, model, vectors.get(index));
			}
		}

		List<Double> queryVector = embed(List.of(queryText), provider, model).getFirst();
		String vector = vectorLiteral(queryVector);
		List<UUID> ids = jdbcTemplate.query(
				"""
				SELECT mentor_id
				FROM mentor_profile_embedding
				WHERE provider = ? AND model = ? AND vector_dims(embedding) = ?
				  AND mentor_id = ANY (?::uuid[])
				ORDER BY embedding <=> CAST(? AS vector), mentor_id
				LIMIT ?
				""",
				ps -> {
					ps.setString(1, provider);
					ps.setString(2, model);
					ps.setInt(3, queryVector.size());
					ps.setArray(4, ps.getConnection().createArrayOf("uuid", byId.keySet().toArray()));
					ps.setString(5, vector);
					ps.setInt(6, Math.min(limit, mentors.size()));
				},
				(rs, row) -> rs.getObject("mentor_id", UUID.class));
		List<MentorProfileResponse> result = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
		return result.isEmpty() ? mentors : result;
	}

	private boolean isCurrent(MentorDocument document, String provider, String model) {
		List<String> fingerprints = jdbcTemplate.queryForList(
				"SELECT source_fingerprint FROM mentor_profile_embedding WHERE mentor_id = ? AND provider = ? AND model = ?",
				String.class, document.id(), provider, model);
		return fingerprints.size() == 1 && fingerprints.getFirst().equals(fingerprint(document.text()));
	}

	private void upsert(MentorDocument document, String provider, String model, List<Double> embedding) {
		jdbcTemplate.update(
				"""
				INSERT INTO mentor_profile_embedding (mentor_id, provider, model, source_fingerprint, embedding)
				VALUES (?, ?, ?, ?, CAST(? AS vector))
				ON CONFLICT (mentor_id, provider, model) DO UPDATE SET
				  source_fingerprint = EXCLUDED.source_fingerprint,
				  embedding = EXCLUDED.embedding,
				  updated_at = CURRENT_TIMESTAMP
				""",
				document.id(), provider, model, fingerprint(document.text()), vectorLiteral(embedding));
	}

	private List<List<Double>> embed(List<String> texts, String provider, String model) throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("provider", provider);
		payload.put("model", model);
		payload.put("texts", texts);
		HttpRequest request = HttpRequest.newBuilder(URI.create(matcherUrl + "/api/embeddings"))
				.timeout(Duration.ofSeconds(45))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("Embedding service returned " + response.statusCode());
		}
		JsonNode values = objectMapper.readTree(response.body()).path("embeddings");
		if (!values.isArray() || values.size() != texts.size()) {
			throw new IllegalStateException("Embedding service returned an invalid vector count");
		}
		List<List<Double>> result = new ArrayList<>();
		for (JsonNode value : values) {
			List<Double> vector = new ArrayList<>();
			value.forEach(number -> vector.add(number.asDouble()));
			if (vector.isEmpty()) throw new IllegalStateException("Embedding service returned an empty vector");
			result.add(vector);
		}
		return result;
	}

	private String mentorText(MentorProfileResponse mentor) {
		String evidence = mentor.recommendationEvidence().stream()
				.map(item -> item.category() + ": " + item.claim() + " — " + item.quote())
				.collect(Collectors.joining("\n"));
		return String.join("\n", mentor.professionalTitle(), mentor.company(), mentor.sectorExperience(),
				mentor.startupStages(), String.join(", ", mentor.specialties()), evidence);
	}

	private String fingerprint(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private String vectorLiteral(List<Double> vector) {
		return vector.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
	}

	private record MentorDocument(UUID id, String text) {}
}
