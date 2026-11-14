package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.time.YearMonth;

import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.entity.MentorExperience;

/** An experience card on a peer mentor's profile (frontend PeerExperienceCard). */
public record PeerExperienceCardResponse(
		PeerExperienceKind kind,
		String title,
		String organization,
		YearMonth startedOn,
		/** Null while it is still going on. */
		YearMonth endedOn,
		String summary) {

	/** A line written before cards had a kind shows as "other". */
	public static PeerExperienceCardResponse of(MentorExperience experience) {
		return new PeerExperienceCardResponse(
				experience.getKind() == null ? PeerExperienceKind.OTHER : experience.getKind(),
				experience.getTitle(),
				experience.getOrganization(),
				month(experience.getStartedOn()),
				month(experience.getEndedOn()),
				experience.getSummary());
	}

	private static YearMonth month(LocalDate day) {
		return day == null ? null : YearMonth.from(day);
	}
}
