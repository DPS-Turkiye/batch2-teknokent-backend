package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
		name = "mentorship_week_check",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_mentorship_week_check",
				columnNames = { "mentorship_id", "week_start" }))
@Getter
@Setter
@NoArgsConstructor
public class MentorshipWeekCheck {

	public static MentorshipWeekCheck create(Mentorship mentorship, LocalDate weekStart, boolean completed) {
		MentorshipWeekCheck check = new MentorshipWeekCheck();
		check.mentorship = mentorship;
		check.weekStart = weekStart;
		check.completed = completed;
		return check;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentorship_id", nullable = false)
	private Mentorship mentorship;

	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	@Column(nullable = false)
	private boolean completed;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
