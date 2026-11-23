package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import com.dps.teknokent.domain.EmailExclusion;
import com.dps.teknokent.domain.EmailRecipientKind;
import com.dps.teknokent.domain.EmailRecipientStatus;
import com.dps.teknokent.domain.UserAccountStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One person on a mail's reviewed list: who they were when it was reviewed, and what became of their mail. */
// Only what changed is written: another job (an alert, an erasure) may write the same row meanwhile.
@DynamicUpdate
@Entity
@Table(name = "email_campaign_recipient", uniqueConstraints = {
		@UniqueConstraint(name = "uq_email_campaign_recipient_address", columnNames = { "campaign_id", "address" }),
		@UniqueConstraint(name = "uq_email_campaign_recipient_message", columnNames = "ses_message_id"),
		@UniqueConstraint(name = "uq_email_campaign_recipient_token", columnNames = "unsubscribe_token_hash") })
@Getter
@Setter
@NoArgsConstructor
public class EmailCampaignRecipient {

	@Id
	private UUID id;

	@Column(name = "campaign_id", nullable = false)
	private UUID campaignId;

	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(name = "pending_member_id")
	private UUID pendingMemberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private EmailRecipientKind kind;

	/** Normalized; null when there is none, or once the account behind it is deleted. */
	@Column(length = 320)
	private String address;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(name = "team_name")
	private String teamName;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_status", length = 16)
	private UserAccountStatus accountStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private EmailRecipientStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 32)
	private EmailExclusion exclusion;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "ses_message_id", length = 128)
	private String sesMessageId;

	@Column(name = "error_code", length = 64)
	private String errorCode;

	@Column(name = "unsubscribe_token_hash", length = 64)
	private String unsubscribeTokenHash;
}
