package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "meeting_feedback")
@Getter
@Setter
@NoArgsConstructor
public class MeetingFeedback {

	@Id
	@GeneratedValue
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "meeting_id", nullable = false)
	private Meeting meeting;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private AppUser author;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** Ordered to match the questions the client renders; index is the question. */
	@ElementCollection
	@CollectionTable(name = "meeting_feedback_rating", joinColumns = @JoinColumn(name = "feedback_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "rating", nullable = false)
	@BatchSize(size = 50)
	private List<Integer> ratings = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "meeting_feedback_benefit", joinColumns = @JoinColumn(name = "feedback_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "benefit", nullable = false)
	@BatchSize(size = 50)
	private List<String> benefits = new ArrayList<>();
}
