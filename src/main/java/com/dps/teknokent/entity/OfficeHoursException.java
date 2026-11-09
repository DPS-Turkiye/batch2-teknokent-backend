package com.dps.teknokent.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Days an academic holds no office hours, both ends included. */
@Entity
@Table(name = "office_hours_exception")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursException {

	@Id
	private UUID id;

	@Column(name = "instructor_id", nullable = false)
	private UUID instructorId;

	@Column(name = "starts_on", nullable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on", nullable = false)
	private LocalDate endsOn;

	@Column(length = 200)
	private String reason;
}
