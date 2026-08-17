package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.TechnologyInferenceRule;
import com.dps.teknokent.domain.TechnologyInferenceRules;
import com.dps.teknokent.util.TextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class TechnologyInferenceService {
	public ObjectNode enrich(ObjectNode profile) {
		List<String> evidenceSentences = evidenceSentences(profile);
		ArrayNode technologies = profile.withArray("technologies");
		Set<String> normalizedTags = new LinkedHashSet<>();
		technologies.forEach(item -> {
			if (item.isTextual()) normalizedTags.add(TextUtils.normalizeForComparison(item.asText()));
		});
		ArrayNode inferences = profile.putArray("technology_inferences");

		for (TechnologyInferenceRule rule : TechnologyInferenceRules.EXPLICIT_RECOVERY) {
			addIfSupported(rule, evidenceSentences, technologies, inferences, normalizedTags);
		}
		for (TechnologyInferenceRule rule : TechnologyInferenceRules.CAPABILITIES) {
			addIfSupported(rule, evidenceSentences, technologies, inferences, normalizedTags);
		}

		String frontendEvidence = firstEvidence(TechnologyInferenceRules.FRONTEND, evidenceSentences);
		String backendEvidence = firstEvidence(TechnologyInferenceRules.BACKEND, evidenceSentences);
		String fullStackEvidence = firstEvidence(TechnologyInferenceRules.EXPLICIT_FULL_STACK, evidenceSentences);
		if (fullStackEvidence != null) {
			addTag(TechnologyInferenceRules.EXPLICIT_FULL_STACK, fullStackEvidence, technologies, inferences, normalizedTags);
		} else if (frontendEvidence != null && backendEvidence != null) {
			String evidence = frontendEvidence.equals(backendEvidence)
					? frontendEvidence
					: frontendEvidence + " | " + backendEvidence;
			addTag(TechnologyInferenceRules.COMBINED_FULL_STACK, evidence, technologies, inferences, normalizedTags);
		}
		return profile;
	}

	private void addIfSupported(
			TechnologyInferenceRule rule,
			List<String> evidenceSentences,
			ArrayNode technologies,
			ArrayNode inferences,
			Set<String> normalizedTags) {
		String evidence = firstEvidence(rule, evidenceSentences);
		if (evidence != null) addTag(rule, evidence, technologies, inferences, normalizedTags);
	}

	private void addTag(
			TechnologyInferenceRule rule,
			String evidence,
			ArrayNode technologies,
			ArrayNode inferences,
			Set<String> normalizedTags) {
		if (!normalizedTags.add(TextUtils.normalizeForComparison(rule.tag()))) return;
		technologies.add(rule.tag());
		ObjectNode inference = inferences.addObject();
		inference.put("tag", rule.tag());
		inference.put("type", rule.type());
		inference.put("confidence", rule.confidence());
		inference.put("evidence", evidence.length() > 500 ? evidence.substring(0, 500) : evidence);
	}

	private String firstEvidence(TechnologyInferenceRule rule, List<String> evidenceSentences) {
		for (String sentence : evidenceSentences) {
			for (var pattern : rule.patterns()) {
				if (pattern.matcher(sentence).find()) return sentence;
			}
		}
		return null;
	}

	private List<String> evidenceSentences(ObjectNode profile) {
		Set<String> values = new LinkedHashSet<>();
		for (String field : TechnologyInferenceRules.EVIDENCE_FIELDS) collectText(profile.path(field), values);
		List<String> sentences = new ArrayList<>();
		for (String value : values) {
			for (String sentence : value.split("(?<=[.!?])\\s+|[\\r\\n]+")) {
				String trimmed = sentence.trim();
				if (!trimmed.isBlank()) sentences.add(trimmed);
			}
		}
		return sentences;
	}

	private void collectText(JsonNode node, Set<String> values) {
		if (node.isTextual()) {
			values.add(node.asText());
		} else if (node.isArray()) {
			node.forEach(item -> collectText(item, values));
		} else if (node.isObject()) {
			node.fields().forEachRemaining(entry -> collectText(entry.getValue(), values));
		}
	}
}
