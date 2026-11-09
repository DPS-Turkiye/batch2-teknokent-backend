package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.PlatformFeedbackKind;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PlatformFeedback;

public record PlatformFeedbackResponse(
		UUID id,
		PlatformFeedbackKind kind,
		String subject,
		String message,
		AppLanguage language,
		String authorName,
		String authorEmail,
		UserRole authorRole,
		Instant createdAt,
		Instant readAt,
		UUID programId,
		String programName,
		ProgramType programType) {

	public static PlatformFeedbackResponse from(PlatformFeedback feedback) {
		AppUser author = feedback.getAuthor();
		return new PlatformFeedbackResponse(
				feedback.getId(), feedback.getKind(), feedback.getSubject(), feedback.getMessage(),
				feedback.getLanguage(), author.getFullName(), author.getEmail(), author.getRole(),
				feedback.getCreatedAt(), feedback.getReadAt(),
				feedback.getProgram() == null ? null : feedback.getProgram().getId(),
				feedback.getProgram() == null ? null : feedback.getProgram().getName(),
				feedback.getProgramType());
	}
}
