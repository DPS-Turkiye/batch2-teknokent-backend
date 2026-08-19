package com.dps.teknokent.domain;

import java.util.List;

public final class TechnologyInferenceRules {
	public static final List<String> EVIDENCE_FIELDS = List.of(
			"professional_summary", "current_role", "professional_functions", "technologies",
			"projects", "achievement_highlights", "employment_highlights", "startup_summary",
			"business_model", "current_bottlenecks");

	public static final List<TechnologyInferenceRule> EXPLICIT_RECOVERY = List.of(
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

	public static final TechnologyInferenceRule WEB_APPLICATION = rule(
			"Web application development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\b(?:built|developed|created|shipped|tasarlad[ıi]|geliştird[ıi]|oluşturdu)\\b[^.]{0,100}\\bweb\\s*(?:app|application|platform|uygulama)",
			"\\bweb\\s+(?:uygulaması|uygulamasi|platformu|tabanlı|tabanli)\\b");

	public static final TechnologyInferenceRule FRONTEND = rule(
			"Frontend development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\bfront[ -]?end\\b", "\\buser interface\\b", "\\bkullanıcı arayüz", "\\breact(?:\\.js)?\\b",
			"\\bnext\\.?js\\b", "\\bvue(?:\\.js)?\\b", "\\bangular\\b");

	public static final TechnologyInferenceRule BACKEND = rule(
			"Backend development", "DERIVED_CAPABILITY", "MEDIUM",
			"\\bback[ -]?end\\b", "\\bserver[ -]?side\\b", "\\bmicroservice", "\\brest(?:ful)? api\\b",
			"\\bspring\\s+boot\\b", "\\bnode\\.?js\\b", "\\bdjango\\b", "\\bfastapi\\b");

	public static final TechnologyInferenceRule EXPLICIT_FULL_STACK = rule(
			"Full-stack development", "DERIVED_CAPABILITY", "HIGH",
			"\\bfull[ -]?stack\\b", "\\bend[ -]to[ -]end\\b[^.]{0,80}\\bweb\\b");

	public static final TechnologyInferenceRule COMBINED_FULL_STACK = rule(
			"Full-stack development", "DERIVED_CAPABILITY", "MEDIUM");

	public static final List<TechnologyInferenceRule> CAPABILITIES = List.of(
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

	private TechnologyInferenceRules() {
	}

	private static TechnologyInferenceRule rule(
			String tag,
			String type,
			String confidence,
			String... patterns) {
		return TechnologyInferenceRule.of(tag, type, confidence, patterns);
	}
}
