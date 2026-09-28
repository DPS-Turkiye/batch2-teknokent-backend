package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

/**
 * Post-meeting ratings of a program's teams, as numbers only.
 * {@code questionAverages} follows the order of the questions the client asks; {@code distribution} counts the
 * answers given 1 to 5. Averages are null until a mentee has rated a meeting.
 */
public record ProgramMeetingRatingsResponse(
		UUID programId,
		int heldMeetingCount,
		int ratedMeetingCount,
		int feedbackCount,
		Double average,
		List<Double> questionAverages,
		List<Integer> distribution) {
}
