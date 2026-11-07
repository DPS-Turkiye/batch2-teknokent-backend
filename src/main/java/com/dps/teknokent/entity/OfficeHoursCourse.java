package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A Moodle course office hours were opened from (the LTI context). */
@Entity
@Table(name = "office_hours_course")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursCourse {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String issuer;

	/** Moodle's course id on that site (the context claim's {@code id}). */
	@Column(name = "context_id", nullable = false)
	private String contextId;

	@Column(name = "short_name")
	private String shortName;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
