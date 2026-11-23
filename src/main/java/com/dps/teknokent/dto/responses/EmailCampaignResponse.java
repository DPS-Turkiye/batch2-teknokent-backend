package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailCampaignStatus;
import com.dps.teknokent.domain.EmailExclusion;
import com.dps.teknokent.domain.EmailRecipientKind;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserAccountStatus;

/** A mail in the list: its text's subject, where it stands, and how many recipients have each status. */
public record EmailCampaignResponse(
		UUID id,
		UUID programId,
		String programName,
		String senderName,
		EmailAudience audience,
		String subject,
		EmailCampaignStatus status,
		@Nullable Instant testSentAt,
		@Nullable Instant confirmedAt,
		@Nullable Instant finishedAt,
		Instant createdAt,
		Map<EmailRecipientStatus, Long> counts) {

	/** One mail with its text and, once reviewed, its people; {@code reviewDigest} is null until it is reviewed. */
	public record Detail(
			EmailCampaignResponse campaign,
			String bodyHtml,
			@Nullable String replyTo,
			@Nullable String reviewDigest,
			List<Recipient> recipients) {
	}

	public record Recipient(
			UUID id,
			String fullName,
			@Nullable String address,
			@Nullable String teamName,
			EmailRecipientKind kind,
			@Nullable UserAccountStatus accountStatus,
			EmailRecipientStatus status,
			@Nullable EmailExclusion exclusion,
			@Nullable String errorCode,
			@Nullable Instant sentAt) {
	}

	/** What the screen offers: the programs the caller may write to, and whether a confirmed mail would leave. */
	public record Options(boolean sendingOn, List<ProgramOption> programs) {
	}

	public record ProgramOption(UUID id, String name) {
	}

	/** A ready text the sender starts from. */
	public record Template(String key, String name, EmailAudience audience, String subject, String bodyHtml) {
	}
}
