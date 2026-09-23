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

/** A teammate the founder listed during signup who has not joined with the invite code yet. */
@Entity
@Table(name = "organization_pending_member")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationPendingMember {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(name = "job_title")
	private String jobTitle;

	private String email;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
