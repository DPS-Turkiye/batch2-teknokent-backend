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

/** Office hours an academic and a student agreed on; face to face, so there is no meeting link. */
@Entity
@Table(name = "office_hours_meeting")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursMeeting {

	@Id
	private UUID id;

	@Column(name = "request_id", nullable = false, unique = true)
	private UUID requestId;

	@Column(name = "course_id", nullable = false)
	private UUID courseId;

	@Column(name = "instructor_id", nullable = false)
	private UUID instructorId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
