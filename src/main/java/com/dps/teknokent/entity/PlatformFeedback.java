package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.PlatformFeedbackKind;
import com.dps.teknokent.domain.ProgramType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "platform_feedback")
@Getter
@Setter
@NoArgsConstructor
public class PlatformFeedback {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "author_id", nullable = false)
	private AppUser author;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PlatformFeedbackKind kind;

	@Column(nullable = false, length = 120)
	private String subject;

	@Column(nullable = false, length = 2000)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private AppLanguage language;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "read_at")
	private Instant readAt;

	/** The author's program when they sent it; null for older feedback and authors outside any program. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "program_id")
	private Program program;

	@Enumerated(EnumType.STRING)
	@Column(name = "program_type", length = 32)
	private ProgramType programType;
}
