package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "program")
@Getter
@Setter
@NoArgsConstructor
public class Program {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ProgramStatus status = ProgramStatus.ACTIVE;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Column(name = "meeting_frequency_days", nullable = false)
	private int meetingFrequencyDays = 14;

	@Column(name = "stale_after_days", nullable = false)
	private int staleAfterDays = 14;

	@Column(name = "meeting_note_required", nullable = false)
	private boolean meetingNoteRequired = true;

	@Column(name = "auto_match_suggestions", nullable = false)
	private boolean autoMatchSuggestions = true;

	@Column(name = "default_mentor_capacity", nullable = false)
	private int defaultMentorCapacity = 4;

	@Column(name = "communication_gap_days", nullable = false)
	private int communicationGapDays = 14;

	@Column(name = "bottleneck_flag_days", nullable = false)
	private int bottleneckFlagDays = 21;

	@Column(name = "syllabus_document_id")
	private UUID syllabusDocumentId;
}
