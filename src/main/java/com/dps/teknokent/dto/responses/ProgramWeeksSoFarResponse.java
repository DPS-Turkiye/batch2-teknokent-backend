package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The weekly plan a participant has reached: each saved week whose first day has come, in week order, so Süreç can
 * list "Geçmiş haftalar" under "Bu hafta". A week that has not begun is never in it, and neither is anything dated
 * after the running week's last day (a session or special day of an earlier week included). Empty before the first
 * week begins; every week once the program is over. {@code currentWeek} is the week "Bu hafta" shows, null between
 * two printed weeks or outside the program.
 */
public record ProgramWeeksSoFarResponse(
		UUID programId,
		String programName,
		@Nullable Integer currentWeek,
		List<Week> weeks) {

	/** {@code current} marks the running week; {@code specialDays} are the ones dated inside the week's days. */
	public record Week(
			ProgramWeekPlanResponse.Week plan,
			boolean current,
			List<ProgramWeekPlanResponse.SpecialDay> specialDays) {
	}
}
