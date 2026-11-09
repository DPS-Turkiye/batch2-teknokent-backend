package com.dps.teknokent.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dps.teknokent.repository.MeetingFeedbackRepository;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

/**
 * Gives the achievement badges for sessions and feedback from before the rules existed. It runs once at each
 * start and is idempotent: a person who already has a badge, or had it revoked, is skipped. Awards made here are
 * silent, so the first deploy doesn't send everyone a burst of notifications; the badges just appear.
 */
@Component
@RequiredArgsConstructor
public class AchievementBackfill {

	private static final Logger log = LoggerFactory.getLogger(AchievementBackfill.class);

	private final MeetingRepository meetings;
	private final MeetingFeedbackRepository feedback;
	private final AchievementService achievements;

	@Value("${badges.achievements.backfill-on-startup:true}")
	private boolean enabled;

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (enabled) {
			run();
		}
	}

	/** Returns how many badges it awarded. Each person is checked in their own transaction. */
	public int run() {
		Set<UUID> people = new LinkedHashSet<>(meetings.findMentorIdsWithHeldMeetings());
		people.addAll(feedback.findRatedMentorIds());
		people.addAll(feedback.findAuthorIds());
		int awarded = 0;
		for (UUID userId : people) {
			try {
				awarded += achievements.evaluate(userId, false).size();
			} catch (RuntimeException failure) {
				log.warn("Achievement back-fill failed for user {}", userId, failure);
			}
		}
		log.info("Achievement back-fill checked {} people and awarded {} badges", people.size(), awarded);
		return awarded;
	}
}
