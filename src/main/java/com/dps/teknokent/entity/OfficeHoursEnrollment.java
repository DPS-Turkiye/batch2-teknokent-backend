package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A person's role in a course, as Moodle gave it on their latest launch from that course. */
@Entity
@Table(name = "office_hours_enrollment")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursEnrollment {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "course_id", nullable = false)
	private UUID courseId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OfficeHoursRole role;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
