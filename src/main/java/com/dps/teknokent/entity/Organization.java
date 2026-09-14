package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.StartupFundingStage;
import com.dps.teknokent.domain.StartupFundingStageConverter;
import com.dps.teknokent.domain.StartupMaturity;
import com.dps.teknokent.domain.StartupMaturityConverter;
import com.dps.teknokent.domain.StartupProgramLevel;
import com.dps.teknokent.domain.StartupProgramLevelConverter;
import com.dps.teknokent.domain.StartupSector;
import com.dps.teknokent.domain.StartupSectorConverter;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.dto.requests.CreateOrganizationRequest;
import com.dps.teknokent.util.TextUtils;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class Organization {

	public static Organization fromRequest(CreateOrganizationRequest request) {
		Organization organization = new Organization();
		organization.name = TextUtils.trim(request.name());
		organization.description = TextUtils.normalizeOptional(request.description());
		return organization;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column
	private String website;

	@Column(columnDefinition = "TEXT")
	private String problem;

	@Column(columnDefinition = "TEXT")
	private String solution;

	@Column(name = "current_solution", columnDefinition = "TEXT")
	private String currentSolution;

	@Column(name = "target_user", columnDefinition = "TEXT")
	private String targetUser;

	@Convert(converter = StartupSectorConverter.class)
	@Column
	private StartupSector sector;

	@Convert(converter = StartupMaturityConverter.class)
	@Column
	private StartupMaturity maturity;

	@Convert(converter = StartupProgramLevelConverter.class)
	@Column(name = "program_level")
	private StartupProgramLevel programLevel;

	@Convert(converter = StartupFundingStageConverter.class)
	@Column(name = "funding_stage", length = 64)
	private StartupFundingStage fundingStage;

	@Column(name = "founded_year")
	private Integer foundedYear;

	@ElementCollection
	@CollectionTable(name = "organization_support_area", joinColumns = @JoinColumn(name = "organization_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "support_area", nullable = false)
	@BatchSize(size = 50)
	private List<String> supportAreas = new ArrayList<>();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "founder_id")
	private AppUser founder;

	@Column(name = "invite_code", unique = true)
	private String inviteCode;

	// The startup's own choice among the programs a coordinator assigned it.
	// Null means no assignment yet, or none chosen.
	@Column(name = "active_program_id")
	private UUID activeProgramId;

	@Column(name = "status_version", length = 32)
	private String statusVersion;

	@Column(name = "status_summary", columnDefinition = "TEXT")
	private String statusSummary;

	@Column(name = "completion_percent")
	private Integer completionPercent;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_stage", length = 32)
	private StartupStage currentStage;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
