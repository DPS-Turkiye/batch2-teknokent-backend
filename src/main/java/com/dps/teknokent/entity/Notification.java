package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.dps.teknokent.domain.NotificationCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipient_id", nullable = false)
	private AppUser recipient;

	@Column(nullable = false, length = 160)
	private String title;

	@Column(nullable = false, length = 500)
	private String body;

	@Column(length = 500)
	private String href;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private NotificationCategory category = NotificationCategory.GENERAL;

	@Column(name = "read_at")
	private Instant readAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
