package com.dps.teknokent.util;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;

/**
 * Which program a meeting counts in. A team can change programs (an admin moves it) and can be in several; what it
 * did in a program stays with that program, whatever program the team is in today.
 */
public final class ProgramActivity {

	private ProgramActivity() {
	}

	/**
	 * The program the meeting was held in, or null when nothing says so: then it counts with the programs its team
	 * is in today, as before programs were recorded.
	 *
	 * <p>A request names its program. A follow-up meeting gets a request of its own that names none, so it takes
	 * the program of its mentor's mentorship with the team: the latest one that had begun by the meeting.
	 *
	 * @param teamMentorships the mentorships of the meeting's team
	 */
	public static UUID programOf(Meeting meeting, Collection<Mentorship> teamMentorships) {
		MentorshipRequest request = meeting.getMentorshipRequest();
		if (request != null && request.getProgram() != null) {
			return request.getProgram().getId();
		}
		if (meeting.getMentor() == null) {
			return null;
		}
		UUID mentorId = meeting.getMentor().getId();
		List<Mentorship> withThisMentor = teamMentorships.stream()
				.filter(mentorship -> mentorship.getProgram() != null
						&& mentorship.getMentor().getId().equals(mentorId))
				.sorted(Comparator.comparing(Mentorship::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
				.toList();
		if (withThisMentor.isEmpty()) {
			return null;
		}
		Instant at = meeting.getScheduledAt();
		// A meeting dated before every mentorship goes with the first of them.
		Mentorship running = withThisMentor.get(0);
		for (Mentorship mentorship : withThisMentor) {
			if (at != null && mentorship.getCreatedAt() != null && !mentorship.getCreatedAt().isAfter(at)) {
				running = mentorship;
			}
		}
		return running.getProgram().getId();
	}

	/**
	 * Whether the meeting counts in the program: it was held in it, or nothing says where it was held and its team
	 * is in the program today.
	 */
	public static boolean countsIn(
			Meeting meeting, UUID programId, boolean teamIsInProgram, Collection<Mentorship> teamMentorships) {
		UUID heldIn = programOf(meeting, teamMentorships);
		return heldIn == null ? teamIsInProgram : heldIn.equals(programId);
	}

	/**
	 * Of these meetings, the ones that count in the program (see {@link #countsIn}).
	 *
	 * @param teams the teams in the program today
	 * @param mentorshipsByTeam every team's mentorships, by organization id
	 */
	public static List<Meeting> heldIn(
			UUID programId,
			Collection<Meeting> meetings,
			Set<UUID> teams,
			Map<UUID, List<Mentorship>> mentorshipsByTeam) {
		return meetings.stream()
				.filter(meeting -> {
					UUID organizationId = meeting.getOrganization().getId();
					return countsIn(meeting, programId, teams.contains(organizationId),
							mentorshipsByTeam.getOrDefault(organizationId, List.of()));
				})
				.toList();
	}
}
