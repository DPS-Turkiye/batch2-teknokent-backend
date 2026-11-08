package com.dps.teknokent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One school on a mentor's profile; kept apart from the work experiences. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MentorEducation {
	@Column(nullable = false, length = 200)
	private String school;

	@Column(length = 200)
	private String degree;

	@Column(length = 100)
	private String period;
}
