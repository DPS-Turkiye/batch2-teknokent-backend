package com.dps.teknokent.entity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mentor_availability_rule")
@Getter
@Setter
@NoArgsConstructor
public class MentorAvailabilityRule {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentor_id", nullable = false)
	private AppUser mentor;

	/** Stored as the ISO day number so it sorts naturally; Monday = 1. */
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
