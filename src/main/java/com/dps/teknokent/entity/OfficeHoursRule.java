package com.dps.teknokent.entity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An academic's weekly range of office hours ("Salı 14:00-17:00"), split into slots of {@code slotMinutes}. */
@Entity
@Table(name = "office_hours_rule")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursRule {

	@Id
	private UUID id;

	@Column(name = "instructor_id", nullable = false)
	private UUID instructorId;

	/** The ISO day number, Monday = 1, as for mentor availability. */
	@Column(name = "day_of_week", nullable = false)
	private short dayOfWeek;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	@Column(name = "slot_minutes", nullable = false)
	private int slotMinutes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public DayOfWeek day() {
		return DayOfWeek.of(dayOfWeek);
	}

	public void setDay(DayOfWeek day) {
		this.dayOfWeek = (short) day.getValue();
	}
}
