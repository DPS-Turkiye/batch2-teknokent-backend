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

/** Someone who opened office hours from Moodle; separate from Menton's Firebase accounts. */
@Entity
@Table(name = "office_hours_user")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursUser {

	@Id
	private UUID id;

	/** The Moodle site that launched them. */
	@Column(nullable = false)
	private String issuer;

	/** Moodle's id for the person on that site (the token's {@code sub}). */
	@Column(nullable = false)
	private String subject;

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
