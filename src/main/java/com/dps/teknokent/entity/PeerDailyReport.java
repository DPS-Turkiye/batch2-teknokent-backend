package com.dps.teknokent.entity;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerMentorSkill;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** "Günlük Rapor": the mentor's own words about one working day; the day's meetings and activities are not copied. */
@Entity
@Table(name = "peer_daily_report")
@Getter
@Setter
@NoArgsConstructor
public class PeerDailyReport extends PeerReportReview {

	@Id
	private UUID id;

	@Column(name = "mentor_id", nullable = false, updatable = false)
	private UUID mentorId;

	@Column(name = "program_id")
	private UUID programId;

	@Column(name = "report_date", nullable = false, updatable = false)
	private LocalDate reportDate;

	@Column(name = "did_today", columnDefinition = "TEXT")
	private String didToday;

	@Column(columnDefinition = "TEXT")
	private String challenge;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "peer_daily_report_skill", joinColumns = @JoinColumn(name = "report_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "skill", length = 40, nullable = false)
	private Set<PeerMentorSkill> skills = EnumSet.noneOf(PeerMentorSkill.class);

	@Column(columnDefinition = "TEXT")
	private String learned;

	@Column(name = "tomorrow_plan", columnDefinition = "TEXT")
	private String tomorrowPlan;
}
