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

/** An academic's meeting defaults, as a mentor's are on their profile. */
@Entity
@Table(name = "office_hours_settings")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursSettings {

	@Id
	@Column(name = "instructor_id")
	private UUID instructorId;

	/** How many students they meet at a time; empty means no limit, as for a mentor. */
	@Column(name = "student_capacity")
	private Integer studentCapacity;

	@Column(name = "default_meeting_duration_minutes", nullable = false)
	private int defaultMeetingDurationMinutes = 45;

	@Column(name = "meeting_buffer_minutes", nullable = false)
	private int meetingBufferMinutes = 15;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
