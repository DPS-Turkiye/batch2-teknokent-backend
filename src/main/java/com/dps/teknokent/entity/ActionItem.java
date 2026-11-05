package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.util.TextUtils;

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
@Table(name = "action_item")
@Getter
@Setter
@NoArgsConstructor
public class ActionItem {

	public static ActionItem fromRequest(
			CreateActionItemRequest request, Organization organization, MentorshipRequest mentorshipRequest,
			Meeting meeting, AppUser owner) {
		ActionItem actionItem = new ActionItem();
		actionItem.organization = organization;
		actionItem.mentorshipRequest = mentorshipRequest;
		actionItem.meeting = meeting;
		actionItem.owner = owner;
		actionItem.title = TextUtils.trim(request.title());
		actionItem.description = TextUtils.normalizeOptional(request.description());
		actionItem.status = ActionItemStatus.OPEN;
		actionItem.dueDate = request.dueDate();
		return actionItem;
	}

	public void apply(UpdateActionItemRequest request, AppUser requestedOwner) {
		if (request.ownerId() != null) owner = requestedOwner;
		if (request.title() != null) title = TextUtils.trim(request.title());
		if (request.description() != null) description = TextUtils.normalizeOptional(request.description());
		if (request.status() != null) status = request.status();
		if (request.dueDate() != null) dueDate = request.dueDate();
		if (request.completionNotes() != null) completionNotes = TextUtils.normalizeOptional(request.completionNotes());
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	// Null for Süreç assignments, which belong to the startup rather than one request.
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mentorship_request_id")
	private MentorshipRequest mentorshipRequest;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "meeting_id")
	private Meeting meeting;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_id", nullable = false)
	private AppUser owner;

	/** Who had the task before they left the team; null for tasks never handed over. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "previous_owner_id")
	private AppUser previousOwner;

	@Column(name = "owner_left_at")
	private Instant ownerLeftAt;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ActionItemStatus status;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "completion_notes", columnDefinition = "TEXT")
	private String completionNotes;

	@Column(name = "deadline_reminded_at")
	private Instant deadlineRemindedAt;

	// The four below are only set on Süreç "Ödevler"; plain action items leave them null.
	@Enumerated(EnumType.STRING)
	@Column(length = 32)
	private StartupStage stage;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "round_id")
	private StartupRound round;

	@Column(name = "submission_document_id")
	private UUID submissionDocumentId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private AppUser createdBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
