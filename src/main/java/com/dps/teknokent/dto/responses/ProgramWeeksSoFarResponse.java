package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The weekly plan a participant may see: each saved week whose first day has come, in week order, so Süreç can list
 * "Geçmiş haftalar" under "Bu hafta", plus the weeks the coordinator opened early ("Yaklaşan haftalar"). A week the
 * coordinator hid is never in it, nor one that has not begun unless opened early, and nothing of the other weeks dated
 * after the running week's last day (a session or special day of an earlier week included). Empty before the first
 * week begins; every week once the program is over (unless the syllabus prints one later). {@code currentWeek} is the
 * running week once it has begun: null between two printed weeks, outside the program, or when a session dated today
 * belongs to a week that has not begun yet. It still names a running week the coordinator hid, which is then not in
 * {@code weeks}.
 */
public record ProgramWeeksSoFarResponse(
		UUID programId,
		String programName,
		@Nullable Integer currentWeek,
		List<Week> weeks) {

	/**
	 * {@code current} marks the running week; {@code upcoming} a week the coordinator opened before it begins
	 * ("Yaklaşan haftalar"). {@code specialDays} are the ones dated inside the week's days.
	 */
	public record Week(
			ProgramWeekPlanResponse.Week plan,
			boolean current,
			boolean upcoming,
			List<ProgramWeekPlanResponse.SpecialDay> specialDays) {

		public Week(ProgramWeekPlanResponse.Week plan, boolean current,
				List<ProgramWeekPlanResponse.SpecialDay> specialDays) {
			this(plan, current, false, specialDays);
		}
	}
}
