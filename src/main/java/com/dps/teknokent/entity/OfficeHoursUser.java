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

/**
 * Someone in office hours: a Moodle person (issuer + subject, from a launch) or a Menton account signed in through
 * ODTÜ ({@code appUserId}, no Moodle identity). One row may carry both once a Moodle link is confirmed.
 */
@Entity
@Table(name = "office_hours_user")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursUser {

	@Id
	private UUID id;

	/** The Moodle site that launched them; empty for a Menton account with no Moodle link. */
	@Column
	private String issuer;

	/** Moodle's id for the person on that site (the token's {@code sub}); empty without a Moodle link. */
	@Column
	private String subject;

	/** The Menton account this person is, when they came in through Menton. */
	@Column(name = "app_user_id", unique = true)
	private UUID appUserId;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	/** Only when the Moodle site shares e-mail addresses with the tool. */
	@Column(length = 320)
	private String email;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_launch_at", nullable = false)
	private Instant lastLaunchAt;
}
