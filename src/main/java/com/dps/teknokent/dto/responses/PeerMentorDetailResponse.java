package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

/**
 * One peer mentor's profile page as a student of the same program sees it (frontend PeerMentorDetail): the card, plus
 * what the wizard published and the weekly hours. Never the e-mail address or the mentor's other programs.
 */
public record PeerMentorDetailResponse(
		UUID mentorId,
		String fullName,
		String department,
		Integer classYear,
		List<PeerTopicCategory> categories,
		String helpWith,
		List<String> recommendationReasons,
		boolean capacityFull,
		List<PublicBadgeResponse> badges,
		boolean availableThisWeek,
		List<String> subTopics,
		List<String> strongCourses,
		List<String> projects,
		String erasmus,
		String linkedinUrl,
		List<MentorAvailabilityRuleResponse> weeklyHours) {

	public static PeerMentorDetailResponse of(PeerMentorCardResponse card, MentorProfileResponse profile,
			String linkedinUrl, List<MentorAvailabilityRuleResponse> weeklyHours) {
		StudentProfileResponse studies = profile.studentProfile();
		return new PeerMentorDetailResponse(card.mentorId(), card.fullName(), card.department(), card.classYear(),
				card.categories(), card.helpWith(), card.recommendationReasons(), card.capacityFull(), card.badges(),
				card.availableThisWeek(),
				profile.offerings() == null ? List.of() : List.copyOf(profile.offerings()),
				studies == null ? List.of() : studies.strongCourses(),
				studies == null ? List.of() : studies.projects(),
				studies == null ? null : studies.erasmus(),
				linkedinUrl,
				List.copyOf(weeklyHours));
	}
}
