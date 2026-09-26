package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MentorPoolStatus;

public final class MentorPoolResponse {

	private MentorPoolResponse() {
	}

	/** A mentor in the pool, or an invite link nobody has signed up with yet (then mentorId is null). */
	public record Row(
			UUID mentorId,
			UUID inviteId,
			String fullName,
			String email,
			MentorPoolStatus status,
			List<String> expertise,
			List<String> programs,
			int activeTeams,
			int capacity,
			Instant since,
			Instant expiresAt,
			String inviteCode) {
	}

	/** The "Uzmanlık doğrulama" pop-up for a mentor waiting for the coordinator's decision. */
	public record Review(
			UUID mentorId,
			String fullName,
			String email,
			String professionalTitle,
			Instant requestedAt,
			boolean viaInvite,
			boolean emailVerified,
			String linkedinUrl,
			String invitedBy,
			List<String> expertise,
			String availability,
			int capacity) {
	}
}
