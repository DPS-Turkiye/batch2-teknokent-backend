package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An office-hours e-mail waiting to be sent, or to be tried again after a failed send. */
@Entity
@Table(name = "office_hours_mail_outbox")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursMailOutbox {

	@Id
	private UUID id;

	@Column(name = "to_address", nullable = false, length = 320)
	private String toAddress;

	@Column(nullable = false, length = 500)
	private String subject;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
