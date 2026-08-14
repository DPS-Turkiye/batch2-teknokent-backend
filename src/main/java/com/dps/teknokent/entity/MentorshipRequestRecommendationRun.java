package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mentorship_request_recommendation_run")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipRequestRecommendationRun {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "request_id", nullable = false, unique = true)
	private MentorshipRequest request;

	@Column(name = "matching_method", nullable = false, length = 64)
	private String matchingMethod;

	@Column(name = "matching_model")
	private String matchingModel;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;

	@Column(name = "startup_name", columnDefinition = "TEXT")
	private String startupName;

	@Column(name = "startup_sector", columnDefinition = "TEXT")
	private String startupSector;

	@Column(name = "startup_stage", columnDefinition = "TEXT")
	private String startupStage;

	@Column(name = "startup_summary", columnDefinition = "TEXT")
	private String startupSummary;

	@ElementCollection
	@CollectionTable(name = "mentorship_request_recommendation_technology", joinColumns = @JoinColumn(name = "run_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "technology", nullable = false)
	private List<String> startupTechnologies = new ArrayList<>();

	@OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<MentorshipRequestRecommendationItem> recommendations = new ArrayList<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
