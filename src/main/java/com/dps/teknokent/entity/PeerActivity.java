package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActivityType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** "Görüşme dışı faaliyet": mentoring work outside a meeting, counted on its day with its duration. */
@Entity
@Table(name = "peer_activity")
@Getter
@Setter
@NoArgsConstructor
public class PeerActivity {

	@Id
	private UUID id;

	@Column(name = "mentor_id", nullable = false, updatable = false)
	private UUID mentorId;

	@Column(name = "program_id")
	private UUID programId;

	@Column(name = "activity_date", nullable = false)
	private LocalDate activityDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "activity_type", nullable = false, length = 24)
	private PeerActivityType activityType;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "mentee_id")
	private UUID menteeId;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
