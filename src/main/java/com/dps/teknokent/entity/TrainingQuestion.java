package com.dps.teknokent.entity;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One quiz question of one version; {@code correctOption} (0–3) never leaves the backend. */
@Entity
@Table(name = "training_question")
@Getter
@Setter
@NoArgsConstructor
public class TrainingQuestion {

	@Id
	private UUID id;

	@Column(name = "module_id", nullable = false)
	private UUID moduleId;

	@Column(nullable = false)
	private int version;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String text;

	@Column(name = "option_1", nullable = false, columnDefinition = "TEXT")
	private String option1;

	@Column(name = "option_2", nullable = false, columnDefinition = "TEXT")
	private String option2;

	@Column(name = "option_3", nullable = false, columnDefinition = "TEXT")
	private String option3;

	@Column(name = "option_4", nullable = false, columnDefinition = "TEXT")
	private String option4;

	@Column(name = "correct_option", nullable = false)
	private short correctOption;

	public List<String> options() {
		return List.of(option1, option2, option3, option4);
	}
}
