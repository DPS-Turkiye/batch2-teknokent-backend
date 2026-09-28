package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.ProgramMeetingRatingsResponse;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

/** Program raporu: the Raporlama numbers for one program. An admin reads any program, a coordinator their own. */
@Service
@RequiredArgsConstructor
public class ProgramReportService {

	private static final int SCALE = 5;

	private final CoordinatorProgramService programService;
	private final AdminOverviewService adminOverviewService;
	private final ProgramStartupRepository programStartupRepository;
	private final MeetingRepository meetingRepository;
	private final MeetingFeedbackRepository meetingFeedbackRepository;

	@Transactional(readOnly = true)
	public AdminOverviewResponse report(UUID actorId, UUID programId) {
		return adminOverviewService.programReport(programService.requireManagedProgram(actorId, programId));
	}

	/**
	 * The mentees' post-meeting ratings for the program's meetings held since {@code from} (all of them when null).
	 * Numbers only: the free-text note a mentee may leave is never read here.
	 */
	@Transactional(readOnly = true)
	public ProgramMeetingRatingsResponse meetingRatings(UUID actorId, UUID programId, LocalDate from) {
		Program program = programService.requireManagedProgram(actorId, programId);
		Set<UUID> teams = new HashSet<>();
		for (ProgramStartup startup : programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())) {
			teams.add(startup.getOrganizationId());
		}
		Instant now = Instant.now();
		Instant since = from == null ? null : from.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		List<UUID> held = teams.isEmpty()
				? List.of()
				: meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(teams).stream()
						.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
						.filter(meeting -> meeting.getScheduledAt() != null
								&& !meeting.getScheduledAt().isAfter(now)
								&& (since == null || !meeting.getScheduledAt().isBefore(since)))
						.map(Meeting::getId)
						.toList();
		List<MeetingFeedback> feedback = held.isEmpty()
				? List.of()
				: meetingFeedbackRepository.findByMeetingIdInAndAuthorRole(held, UserRole.MENTEE);
		return summarize(program.getId(), held.size(), feedback);
	}

	static ProgramMeetingRatingsResponse summarize(UUID programId, int heldMeetings, List<MeetingFeedback> feedback) {
		List<Integer> sums = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		List<Integer> distribution = new ArrayList<>(List.of(0, 0, 0, 0, 0));
		Set<UUID> ratedMeetings = new HashSet<>();
		int total = 0;
		int answers = 0;
		for (MeetingFeedback item : feedback) {
			ratedMeetings.add(item.getMeeting().getId());
			List<Integer> ratings = item.getRatings();
			for (int question = 0; question < ratings.size(); question++) {
				int rating = ratings.get(question);
				if (rating < 1 || rating > SCALE) {
					continue;
				}
				while (sums.size() <= question) {
					sums.add(0);
					counts.add(0);
				}
				sums.set(question, sums.get(question) + rating);
				counts.set(question, counts.get(question) + 1);
				distribution.set(rating - 1, distribution.get(rating - 1) + 1);
				total += rating;
				answers++;
			}
		}
		List<Double> questionAverages = new ArrayList<>();
		for (int question = 0; question < sums.size(); question++) {
			questionAverages.add(counts.get(question) == 0 ? null : round((double) sums.get(question) / counts.get(question)));
		}
		return new ProgramMeetingRatingsResponse(
				programId,
				heldMeetings,
				ratedMeetings.size(),
				feedback.size(),
				answers == 0 ? null : round((double) total / answers),
				questionAverages,
				distribution);
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}
}
