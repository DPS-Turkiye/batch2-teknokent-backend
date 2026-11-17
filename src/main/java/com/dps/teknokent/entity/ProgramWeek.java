package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One week of a program's plan. {@code startDate}/{@code endDate} are the dates its syllabus prints (both or
 * neither); without them the week's dates come from the program's start and end (ProgramCalendar).
 */
@Entity
@Table(name = "program_week", uniqueConstraints = @UniqueConstraint(name = "uq_program_week", columnNames = {
		"program_id", "week_number" }))
@Getter
@Setter
@NoArgsConstructor
public class ProgramWeek {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "week_number", nullable = false)
	private int weekNumber;

	@Column(nullable = false, length = 160)
	private String title;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@ElementCollection
	@CollectionTable(name = "program_week_session", joinColumns = @JoinColumn(name = "program_week_id"))
	@OrderColumn(name = "position")
	@BatchSize(size = 50)
	private List<ProgramWeekSession> sessions = new ArrayList<>();

	@Column(nullable = false, columnDefinition = "TEXT")
	private String topics = "";

	@Column(nullable = false, columnDefinition = "TEXT")
	private String activities = "";

	@Column(nullable = false, columnDefinition = "TEXT")
	private String deliverables = "";

	@Column(nullable = false)
	private boolean inferred;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by")
	private UUID updatedBy;

	/** The stored lines of a list column; blank lines are dropped. */
	public static List<String> lines(String stored) {
		return stored == null ? List.of()
				: Arrays.stream(stored.split("\n")).map(String::trim).filter(line -> !line.isEmpty()).toList();
	}

	public static String joined(List<String> lines) {
		return lines == null ? ""
				: String.join("\n", lines.stream().map(String::trim).filter(l -> !l.isEmpty()).toList());
	}
}
