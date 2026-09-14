package com.dps.teknokent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
}
