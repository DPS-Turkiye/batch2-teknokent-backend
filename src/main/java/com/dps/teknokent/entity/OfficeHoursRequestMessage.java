package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursMessageType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "office_hours_request_message")
@Getter
@Setter
@NoArgsConstructor
public class OfficeHoursRequestMessage {

	@Id
	private UUID id;

	@Column(name = "request_id", nullable = false)
	private UUID requestId;

	@Column(name = "author_id", nullable = false)
	private UUID authorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private OfficeHoursMessageType type;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
