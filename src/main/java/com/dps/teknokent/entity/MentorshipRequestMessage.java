package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.dps.teknokent.domain.MentorshipRequestMessageType;

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
@Table(name = "mentorship_request_message")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipRequestMessage {
	public static MentorshipRequestMessage create(
			MentorshipRequest request, AppUser author, MentorshipRequestMessageType messageType, String body) {
		MentorshipRequestMessage message = new MentorshipRequestMessage();
		message.request = request;
		message.author = author;
		message.messageType = messageType;
		message.body = body.trim();
		return message;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "request_id", nullable = false)
	private MentorshipRequest request;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private AppUser author;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false)
	private MentorshipRequestMessageType messageType;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
