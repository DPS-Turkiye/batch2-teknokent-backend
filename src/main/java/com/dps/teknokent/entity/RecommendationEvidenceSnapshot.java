package com.dps.teknokent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.dps.teknokent.dto.responses.MentorEvidenceResponse;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class RecommendationEvidenceSnapshot {
	public static RecommendationEvidenceSnapshot fromEvidence(MentorEvidenceResponse evidence) {
		RecommendationEvidenceSnapshot snapshot = new RecommendationEvidenceSnapshot();
		snapshot.category = evidence.category();
		snapshot.claim = evidence.claim();
		snapshot.quote = evidence.quote();
		snapshot.sourcePage = evidence.sourcePage();
		snapshot.profileVersion = evidence.profileVersion();
		return snapshot;
	}

	@Column(nullable = false, length = 80)
	private String category;

	@Column(nullable = false, length = 1000)
	private String claim;

	@Column(name = "evidence_quote", nullable = false, length = 1500)
	private String quote;

	@Column(name = "source_page")
	private Integer sourcePage;

	@Column(name = "profile_version", nullable = false, length = 100)
	private String profileVersion;
}
