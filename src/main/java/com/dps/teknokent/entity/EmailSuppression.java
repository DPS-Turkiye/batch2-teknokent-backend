package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.EmailSuppressionReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An address, as a hash, that the email center no longer writes to. */
@Entity
@Table(name = "email_suppression")
@Getter
@Setter
@NoArgsConstructor
public class EmailSuppression {

	@Id
	private UUID id;

	@Column(name = "address_hash", nullable = false, unique = true, length = 64)
	private String addressHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private EmailSuppressionReason reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
