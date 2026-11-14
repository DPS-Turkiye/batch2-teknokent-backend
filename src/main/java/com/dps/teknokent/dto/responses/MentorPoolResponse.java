package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MentorPoolStatus;

public final class MentorPoolResponse {

	private MentorPoolResponse() {
	}

	/** A mentor in the pool, or an invite link nobody has signed up with yet (then mentorId is null). */
	public record Row(
			@Nullable UUID mentorId,
			@Nullable UUID inviteId,
			@Nullable String fullName,
			String email,
			MentorPoolStatus status,
			List<String> expertise,
			List<String> programs,
			int activeTeams,
			int capacity,
			Instant since,
			@Nullable Instant expiresAt,
			@Nullable String inviteCode) {
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
			@Nullable String invitedBy,
			List<String> expertise,
			@Nullable String availability,
			int capacity) {
	}
}
