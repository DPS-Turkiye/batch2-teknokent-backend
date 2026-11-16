package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The program's whole weekly plan and its special days; an empty list removes them. Week numbers must be unique.
 * A week's {@code startDate}/{@code endDate} are its own dates (both or neither); without them it follows the
 * program calendar. Missing lists count as empty.
 */
public record ReplaceProgramWeeksRequest(
		UUID actorId,
		@NotNull @Size(max = 104) List<@Valid @NotNull WeekInput> weeks,
		@Size(max = ReplaceProgramWeeksRequest.MAX_SPECIAL_DAYS) List<@Valid @NotNull SpecialDayInput> specialDays) {

	public ReplaceProgramWeeksRequest(UUID actorId, List<WeekInput> weeks) {
		this(actorId, weeks, null);
	}

	public static final int MAX_SPECIAL_DAYS = 20;
	public static final int MAX_SESSIONS = 12;

	public record WeekInput(
			@Min(1) @Max(104) int week,
			@NotBlank @Size(max = 160) String title,
			@Size(max = 12) List<@NotBlank @Size(max = 200) String> topics,
			@Size(max = 12) List<@NotBlank @Size(max = 200) String> activities,
			@Size(max = 12) List<@NotBlank @Size(max = 200) String> deliverables,
			boolean inferred,
			LocalDate startDate,
			LocalDate endDate,
			@Size(max = MAX_SESSIONS) List<@Valid @NotNull SessionInput> sessions) {
		public WeekInput(int week, String title, List<String> topics, List<String> activities,
				List<String> deliverables, boolean inferred) {
			this(week, title, topics, activities, deliverables, inferred, null, null, null);
		}
	}

	public record SessionInput(
			@NotNull LocalDate date,
			LocalTime startTime,
			LocalTime endTime,
			@Size(max = 160) String place,
			@Size(max = 160) String title) {
	}

	public record SpecialDayInput(
			@NotNull LocalDate date,
			LocalTime startTime,
			LocalTime endTime,
			@Size(max = 160) String place,
			@NotBlank @Size(max = 160) String title,
			@Size(max = 12) List<@NotBlank @Size(max = 200) String> details) {
	}
}
