package com.dps.teknokent.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** "Haftalık Özet": three answers of the mentor's; the numbers are computed, and frozen in the snapshot when sent. */
@Entity
@Table(name = "peer_weekly_report")
@Getter
@Setter
@NoArgsConstructor
public class PeerWeeklyReport extends PeerReportReview {

	@Id
	private UUID id;

	@Column(name = "mentor_id", nullable = false, updatable = false)
	private UUID mentorId;

	@Column(name = "program_id")
	private UUID programId;

	/** The Monday of the week. */
	@Column(name = "week_start", nullable = false, updatable = false)
	private LocalDate weekStart;

	@Column(name = "needs_observed", columnDefinition = "TEXT")
	private String needsObserved;

	@Column(columnDefinition = "TEXT")
	private String growth;

	@Column(name = "next_week_plan", columnDefinition = "TEXT")
	private String nextWeekPlan;

	/** The JSON of the computed summary as it was sent; null while a draft. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "summary_snapshot")
	private String summarySnapshot;
}
