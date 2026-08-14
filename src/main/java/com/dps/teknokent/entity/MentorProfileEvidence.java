package com.dps.teknokent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class MentorProfileEvidence {
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
