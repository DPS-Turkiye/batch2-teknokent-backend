package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mentorship_request_recommendation_item")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipRequestRecommendationItem {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private MentorshipRequestRecommendationRun run;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentor_id", nullable = false)
	private AppUser mentor;

	@Column(nullable = false)
	private int rank;

	@Column(nullable = false)
	private boolean selected;

	@Column(name = "mentor_name", nullable = false, columnDefinition = "TEXT")
	private String mentorName;

	@Column(name = "professional_title", nullable = false, columnDefinition = "TEXT")
	private String professionalTitle;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String company;

	@ElementCollection
	@CollectionTable(name = "mentorship_request_recommendation_reason", joinColumns = @JoinColumn(name = "item_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "reason", nullable = false, length = 1000)
	private List<String> reasons = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "mentorship_request_recommendation_evidence", joinColumns = @JoinColumn(name = "item_id"))
	@OrderColumn(name = "display_order")
	private List<RecommendationEvidenceSnapshot> evidence = new ArrayList<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
