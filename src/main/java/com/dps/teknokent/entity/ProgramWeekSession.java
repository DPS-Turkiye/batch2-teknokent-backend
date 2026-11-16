package com.dps.teknokent.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One session of a program week on its own day, as the syllabus prints it. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProgramWeekSession {

	@Column(name = "session_date", nullable = false)
	private LocalDate date;

	@Column(name = "start_time")
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

	@Column(length = 160)
	private String place;

	@Column(length = 160)
	private String title;
}
