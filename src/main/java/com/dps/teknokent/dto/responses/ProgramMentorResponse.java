package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;

public record ProgramMentorResponse(
		UUID mentorId,
		String fullName,
		String email,
		ProgramMentorStatus status,
		ProgramMentorInvitedBy invitedBy,
		Instant createdAt) {
}
