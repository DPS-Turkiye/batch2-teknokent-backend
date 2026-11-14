package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRequestStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A student's request for office hours with one academic of a course. */
@Entity
@Table(name = "office_hours_request")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursRequest {

	@Id
	private UUID id;

	/** Empty for a request made from Menton, which belongs to no course. */
	@Column(name = "course_id")
	private UUID courseId;

	@Column(name = "student_id", nullable = false)
	private UUID studentId;

	@Column(name = "instructor_id", nullable = false)
	private UUID instructorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OfficeHoursRequestStatus status;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String topic;

	@Column(name = "expected_outcome", columnDefinition = "TEXT")
	private String expectedOutcome;

	/** The times the student asked for, comma-separated "yyyy-MM-ddTHH:mm" in Turkey time. */
	@Column(name = "preferred_slots", nullable = false, length = 100)
	private String preferredSlots;

	/** Other times the academic offered instead. */
	@Column(name = "proposed_slots", length = 100)
	private String proposedSlots;

	@Column(name = "instructor_message", columnDefinition = "TEXT")
	private String instructorMessage;

	@Column(name = "rejection_reason", columnDefinition = "TEXT")
	private String rejectionReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
