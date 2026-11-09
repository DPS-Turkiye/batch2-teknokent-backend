package com.dps.teknokent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AchievementProgressResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingFeedback;
import com.dps.teknokent.entity.UserBadge;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.UserBadgeRepository;

import lombok.RequiredArgsConstructor;

/**
 * The achievement badges (design §4.2), awarded by rules from what mentors confirmed as held and what mentees
 * rated. Sessions count the way the program report (F5) counts them: only meetings the mentor confirmed as held
 * and not cancelled, a group session once at its longest planned length. The rating is the report's
 * "Memnuniyet": every 1–5 answer in mentees' feedback. Achievements are platform-wide and never taken back by
 * a rule (D3); one that was revoked by hand is not awarded again.
 */
@Service
@RequiredArgsConstructor
public class AchievementService {

	static final int SESSIONS_FOR_TEN = 10;
	static final int MINUTES_FOR_HOURS = 25 * 60;
	static final int RATINGS_FOR_WELL_RATED = 5;
	static final double AVERAGE_FOR_WELL_RATED = 4.5;
	static final int SESSIONS_FOR_ACTIVE_MENTEE = 3;
	private static final int SCALE = 5;

	private static final Logger log = LoggerFactory.getLogger(AchievementService.class);

	private final MeetingRepository meetings;
	private final MeetingFeedbackRepository feedback;
	private final UserBadgeRepository awards;
	private final AppUserRepository users;
	private final BadgeService badgeService;

	/** After the session or feedback is committed, so a rule that fails never undoes it. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onChange(AchievementsMayChangeEvent event) {
		for (UUID userId : event.userIds()) {
			try {
				evaluate(userId, true);
			} catch (RuntimeException failure) {
				log.warn("Achievement check failed for user {}", userId, failure);
			}
		}
	}

	/** Awards every achievement the person has reached and doesn't hold yet; returns the ones awarded now. */
	@Transactional
	public List<BadgeCode> evaluate(UUID userId, boolean announce) {
		AppUser user = users.findById(userId).orElse(null);
		if (user == null) {
			return List.of();
		}
		Progress progress = progressOf(userId);
		List<BadgeCode> awarded = new ArrayList<>();
		for (BadgeCode code : BadgeCode.values()) {
			if (code.kind() == BadgeKind.ACHIEVEMENT && progress.reached(code)
					&& !awards.existsByUserIdAndBadgeCode(userId, code)) {
				badgeService.award(user, code, null, BadgeSource.RULE, null, announce);
				awarded.add(code);
			}
		}
		return awarded;
	}

	/** Rozetlerim: each achievement, earned or with how far the person is. */
	@Transactional(readOnly = true)
	public List<AchievementProgressResponse> mine(UUID actorId) {
		Map<BadgeCode, Instant> active = new HashMap<>();
		for (UserBadge award : awards.findByUserIdAndStatusOrderByAwardedAtDesc(actorId, BadgeStatus.ACTIVE)) {
			active.putIfAbsent(award.getBadgeCode(), award.getAwardedAt());
		}
		Progress progress = progressOf(actorId);
		List<AchievementProgressResponse> rows = new ArrayList<>();
		for (BadgeCode code : BadgeCode.values()) {
			if (code.kind() != BadgeKind.ACHIEVEMENT) {
				continue;
			}
			boolean earned = active.containsKey(code);
			rows.add(switch (code) {
				case FIRST_SESSION -> row(code, earned, active, progress.sessions(), 1);
				case SESSIONS_10 -> row(code, earned, active, progress.sessions(), SESSIONS_FOR_TEN);
				case HOURS_25 -> row(code, earned, active, progress.minutes(), MINUTES_FOR_HOURS);
				case WELL_RATED -> new AchievementProgressResponse(code, earned, active.get(code),
						progress.ratedFeedback(), RATINGS_FOR_WELL_RATED, progress.average(), AVERAGE_FOR_WELL_RATED);
				case ACTIVE_MENTEE -> row(code, earned, active, progress.menteeSessions(), SESSIONS_FOR_ACTIVE_MENTEE);
				default -> throw new IllegalStateException("Unhandled achievement " + code);
			});
		}
		return rows;
	}

	private static AchievementProgressResponse row(BadgeCode code, boolean earned, Map<BadgeCode, Instant> active,
			int current, int target) {
		return new AchievementProgressResponse(code, earned, active.get(code), current, target, null, null);
	}

	Progress progressOf(UUID userId) {
		Map<Instant, Integer> slots = new HashMap<>();
		for (Meeting meeting : meetings.findByMentorIdAndHeldConfirmedAtIsNotNull(userId)) {
			if (meeting.getStatus() != MeetingStatus.CANCELLED && meeting.getScheduledAt() != null) {
				int minutes = meeting.getDurationMinutes() == null ? 0 : meeting.getDurationMinutes();
				slots.merge(meeting.getScheduledAt(), minutes, Math::max);
			}
		}
		int minutes = slots.values().stream().mapToInt(Integer::intValue).sum();

		int ratedFeedback = 0;
		int total = 0;
		int answers = 0;
		for (MeetingFeedback item : feedback.findByMeetingMentorIdAndAuthorRole(userId, UserRole.MENTEE)) {
			if (item.getAuthor().getId().equals(userId) || item.getMeeting().getStatus() == MeetingStatus.CANCELLED) {
				continue;
			}
			boolean counted = false;
			for (Integer rating : item.getRatings()) {
				if (rating != null && rating >= 1 && rating <= SCALE) {
					total += rating;
					answers++;
					counted = true;
				}
			}
			if (counted) {
				ratedFeedback++;
			}
		}
		Double average = answers == 0 ? null : (double) total / answers;

		Set<UUID> menteeSessions = new HashSet<>();
		for (MeetingFeedback item : feedback.findByAuthorId(userId)) {
			Meeting meeting = item.getMeeting();
			boolean ownSession = meeting.getMentor() != null && meeting.getMentor().getId().equals(userId);
			if (!ownSession && meeting.getHeldConfirmedAt() != null && meeting.getStatus() != MeetingStatus.CANCELLED) {
				menteeSessions.add(meeting.getId());
			}
		}
		return new Progress(slots.size(), minutes, ratedFeedback, average, menteeSessions.size());
	}

	record Progress(int sessions, int minutes, int ratedFeedback, Double average, int menteeSessions) {

		boolean reached(BadgeCode code) {
			return switch (code) {
				case FIRST_SESSION -> sessions >= 1;
				case SESSIONS_10 -> sessions >= SESSIONS_FOR_TEN;
				case HOURS_25 -> minutes >= MINUTES_FOR_HOURS;
				case WELL_RATED -> ratedFeedback >= RATINGS_FOR_WELL_RATED && average != null
						&& average >= AVERAGE_FOR_WELL_RATED;
				case ACTIVE_MENTEE -> menteeSessions >= SESSIONS_FOR_ACTIVE_MENTEE;
				default -> false;
			};
		}
	}
}
