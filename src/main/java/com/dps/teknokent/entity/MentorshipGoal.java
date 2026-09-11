package com.dps.teknokent.entity;

import java.time.Instant;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mentorship_goal")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipGoal {

	public static MentorshipGoal create(Mentorship mentorship, String title, int sortOrder) {
		MentorshipGoal goal = new MentorshipGoal();
		goal.mentorship = mentorship;
		goal.title = title;
		goal.done = false;
		goal.sortOrder = sortOrder;
		return goal;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentorship_id", nullable = false)
	private Mentorship mentorship;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String title;

	@Column(nullable = false)
	private boolean done;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
