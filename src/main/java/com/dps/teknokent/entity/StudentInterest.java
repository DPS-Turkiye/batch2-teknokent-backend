package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An ODTÜ student's interests and goal (profile completion and Profilim). */
@Entity
@Table(name = "student_interest")
@Getter
@Setter
@NoArgsConstructor
public class StudentInterest {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@ElementCollection
	@CollectionTable(name = "student_interest_topic", joinColumns = @JoinColumn(name = "user_id"))
	@OrderColumn(name = "display_order")
	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false, length = 40)
	private List<PeerTopicCategory> topics = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "student_interest_subtopic", joinColumns = @JoinColumn(name = "user_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "subtopic", nullable = false, length = 64)
	private List<String> subtopics = new ArrayList<>();

	@Column(nullable = false, length = 1000)
	private String goal = "";

	@Column(nullable = false)
	private boolean completed;

	@Column(nullable = false)
	private boolean skipped;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public StudentInterest(UUID userId) {
		this.userId = userId;
	}
}
