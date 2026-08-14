package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class TechnologyInferenceService {
	private static final List<String> EVIDENCE_FIELDS = List.of(
			"professional_summary", "current_role", "professional_functions", "technologies",
			"projects", "achievement_highlights", "employment_highlights", "startup_summary",
			"business_model", "current_bottlenecks");

	private static final List<TechnologyRule> EXPLICIT_RECOVERY_RULES = List.of(
			rule("React", "EXPLICIT_RECOVERY", "HIGH", "\\breact(?:\\.js)?\\b"),
			rule("Next.js", "EXPLICIT_RECOVERY", "HIGH", "\\bnext\\.?js\\b"),
			rule("Vue.js", "EXPLICIT_RECOVERY", "HIGH", "\\bvue(?:\\.js)?\\b"),
			rule("Angular", "EXPLICIT_RECOVERY", "HIGH", "\\bangular\\b"),
			rule("Node.js", "EXPLICIT_RECOVERY", "HIGH", "\\bnode\\.?js\\b"),
			rule("Spring Boot", "EXPLICIT_RECOVERY", "HIGH", "\\bspring\\s+boot\\b"),
			rule("Java", "EXPLICIT_RECOVERY", "HIGH", "\\bjava\\b"),
			rule("Python", "EXPLICIT_RECOVERY", "HIGH", "\\bpython\\b"),
			rule("PostgreSQL", "EXPLICIT_RECOVERY", "HIGH", "\\bpostgres(?:ql)?\\b"),
			rule("MongoDB", "EXPLICIT_RECOVERY", "HIGH", "\\bmongodb\\b"),
			rule("Docker", "EXPLICIT_RECOVERY", "HIGH", "\\bdocker\\b"),
			rule("Kubernetes", "EXPLICIT_RECOVERY", "HIGH", "\\bkubernetes\\b|\\bk8s\\b"),
			rule("AWS", "EXPLICIT_RECOVERY", "HIGH", "\\baws\\b|amazon web services"),
			rule("Microsoft Azure", "EXPLICIT_RECOVERY", "HIGH", "\\bazure\\b"),
			rule("Google Cloud", "EXPLICIT_RECOVERY", "HIGH", "\\bgcp\\b|google cloud"),
			rule("TensorFlow", "EXPLICIT_RECOVERY", "HIGH", "\\btensorflow\\b"),
			rule("PyTorch", "EXPLICIT_RECOVERY", "HIGH", "\\bpytorch\\b"));

	private static final TechnologyRule WEB_APPLICATION = rule(
			"Web application development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\b(?:built|developed|created|shipped|tasarlad[ıi]|geliştird[ıi]|oluşturdu)\\b[^.]{0,100}\\bweb\\s*(?:app|application|platform|uygulama)",
			"\\bweb\\s+(?:uygulaması|uygulamasi|platformu|tabanlı|tabanli)\\b");
	private static final TechnologyRule FRONTEND = rule(
			"Frontend development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\bfront[ -]?end\\b", "\\buser interface\\b", "\\bkullanıcı arayüz", "\\breact(?:\\.js)?\\b",
			"\\bnext\\.?js\\b", "\\bvue(?:\\.js)?\\b", "\\bangular\\b");
	private static final TechnologyRule BACKEND = rule(
			"Backend development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\bback[ -]?end\\b", "\\bserver[ -]?side\\b", "\\bmicroservice", "\\brest(?:ful)? api\\b",
			"\\bspring\\s+boot\\b", "\\bnode\\.?js\\b", "\\bdjango\\b", "\\bfastapi\\b");
	private static final List<TechnologyRule> CAPABILITY_RULES = List.of(
			WEB_APPLICATION,
			FRONTEND,
			BACKEND,
			rule("Mobile application development", "DERIVED_CAPABILITY", "MEDIUM",
					"\\bmobile\\s+(?:app|application)\\b", "\\bmobil\\s+uygulama", "\\bios\\b", "\\bandroid\\b",
					"\\bflutter\\b", "\\breact native\\b"),
			rule("Data engineering", "DERIVED_CAPABILITY", "MEDIUM",
					"\\bdata pipeline", "\\betl\\b", "\\bdata warehouse", "\\bevent[ -]?driven.*pipeline",
					"\\bveri hatt", "\\bveri platform"),
			rule("MLOps", "DERIVED_CAPABILITY", "HIGH",
					"\\bmlops\\b", "\\bmodel monitoring\\b", "\\bmodel version", "\\bmodel izleme",
					"\\bmakine öğrenmesi servis"),
			rule("Machine learning", "DERIVED_CAPABILITY", "MEDIUM",
					"\\bmachine learning\\b", "\\bmakine öğrenmesi\\b", "\\byapay zeka model", "\\bneural network"),
			rule("DevOps", "DERIVED_CAPABILITY", "MEDIUM",
					"\\bdevops\\b", "\\bci\\s*/\\s*cd\\b", "\\bcontinuous (?:integration|delivery|deployment)\\b"),
			rule("Cloud infrastructure", "DERIVED_CAPABILITY", "MEDIUM",
					"\\bcloud infrastructure\\b", "\\bbulut altyap", "\\bkubernetes\\b", "\\baws\\b", "\\bazure\\b", "google cloud"));

	public ObjectNode enrich(ObjectNode profile) {
		List<String> evidenceSentences = evidenceSentences(profile);
		ArrayNode technologies = profile.withArray("technologies");
		Set<String> normalizedTags = new LinkedHashSet<>();
		technologies.forEach(item -> {
			if (item.isTextual()) normalizedTags.add(normalizeTag(item.asText()));
		});
		ArrayNode inferences = profile.putArray("technology_inferences");

		for (TechnologyRule rule : EXPLICIT_RECOVERY_RULES) {
			addIfSupported(rule, evidenceSentences, technologies, inferences, normalizedTags);
		}
		for (TechnologyRule rule : CAPABILITY_RULES) {
			addIfSupported(rule, evidenceSentences, technologies, inferences, normalizedTags);
		}

		String frontendEvidence = firstEvidence(FRONTEND, evidenceSentences);
		String backendEvidence = firstEvidence(BACKEND, evidenceSentences);
		TechnologyRule explicitFullStack = rule(
				"Full-stack development", "DERIVED_CAPABILITY", "HIGH",
				"\\bfull[ -]?stack\\b", "\\bend[ -]to[ -]end\\b[^.]{0,80}\\bweb\\b");
		String fullStackEvidence = firstEvidence(explicitFullStack, evidenceSentences);
		if (fullStackEvidence != null) {
			addTag(explicitFullStack, fullStackEvidence, technologies, inferences, normalizedTags);
		} else if (frontendEvidence != null && backendEvidence != null) {
			TechnologyRule combined = rule("Full-stack development", "DERIVED_CAPABILITY", "MEDIUM");
			String evidence = frontendEvidence.equals(backendEvidence)
					? frontendEvidence
					: frontendEvidence + " | " + backendEvidence;
			addTag(combined, evidence, technologies, inferences, normalizedTags);
		}
		return profile;
	}

	private void addIfSupported(
			TechnologyRule rule,
			List<String> evidenceSentences,
			ArrayNode technologies,
			ArrayNode inferences,
			Set<String> normalizedTags) {
		String evidence = firstEvidence(rule, evidenceSentences);
		if (evidence != null) addTag(rule, evidence, technologies, inferences, normalizedTags);
	}

	private void addTag(
			TechnologyRule rule,
			String evidence,
			ArrayNode technologies,
			ArrayNode inferences,
			Set<String> normalizedTags) {
		if (!normalizedTags.add(normalizeTag(rule.tag()))) return;
		technologies.add(rule.tag());
		ObjectNode inference = inferences.addObject();
		inference.put("tag", rule.tag());
		inference.put("type", rule.type());
		inference.put("confidence", rule.confidence());
		inference.put("evidence", evidence.length() > 500 ? evidence.substring(0, 500) : evidence);
	}

	private String firstEvidence(TechnologyRule rule, List<String> evidenceSentences) {
		for (String sentence : evidenceSentences) {
			for (Pattern pattern : rule.patterns()) {
				if (pattern.matcher(sentence).find()) return sentence;
			}
		}
		return null;
	}

	private List<String> evidenceSentences(ObjectNode profile) {
		Set<String> values = new LinkedHashSet<>();
		for (String field : EVIDENCE_FIELDS) collectText(profile.path(field), values);
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

	private String normalizeTag(String tag) {
		return tag.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}

	private static TechnologyRule rule(String tag, String type, String confidence, String... patterns) {
		return new TechnologyRule(tag, type, confidence,
				java.util.Arrays.stream(patterns).map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList());
	}

	private record TechnologyRule(String tag, String type, String confidence, List<Pattern> patterns) {
	}
}
