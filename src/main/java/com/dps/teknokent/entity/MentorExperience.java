package com.dps.teknokent.entity;

import java.time.LocalDate;

import com.dps.teknokent.domain.PeerExperienceKind;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of a mentor's experience. Teknokent mentors write a free-text period; İŞKUR peer mentors write cards with a
 * kind and start/end months (the first of the month), and leave the period empty.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MentorExperience {

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 200)
	private String organization;

	@Column(length = 100)
	private String period;

	@Column(length = 300)
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private PeerExperienceKind kind;

	@Column(name = "started_on")
	private LocalDate startedOn;

	/** Empty while it is still going on. */
	@Column(name = "ended_on")
	private LocalDate endedOn;

	public MentorExperience(String title, String organization, String period, String summary) {
		this(title, organization, period, summary, null, null, null);
	}
}
