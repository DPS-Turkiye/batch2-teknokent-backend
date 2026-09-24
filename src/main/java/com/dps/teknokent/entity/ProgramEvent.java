package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramEventKind;

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
@Table(name = "program_event")
@Getter
@Setter
@NoArgsConstructor
public class ProgramEvent {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(nullable = false, length = 160)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProgramEventKind kind;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
