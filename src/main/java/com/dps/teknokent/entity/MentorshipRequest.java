package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
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
@Table(name = "mentorship_request")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipRequest {

	public static MentorshipRequest fromRequest(
			CreateMentorshipRequestSubmission request,
			AppUser requester,
			Organization organization,
			AppUser mentor) {
		MentorshipRequest mentorshipRequest = new MentorshipRequest();
		mentorshipRequest.requester = requester;
		mentorshipRequest.organization = organization;
		mentorshipRequest.mentor = mentor;
		mentorshipRequest.requestedForName = TextUtils.trim(request.requestedForName());
		mentorshipRequest.supportArea = TextUtils.trim(request.supportArea());
		mentorshipRequest.summary = TextUtils.trim(request.summary());
		mentorshipRequest.urgency = request.urgency();
		mentorshipRequest.mentorshipFormat = request.mentorshipFormat();
		mentorshipRequest.meetingMode = request.meetingMode();
		mentorshipRequest.status = MentorshipRequestStatus.PENDING;
		mentorshipRequest.requesterMessage = TextUtils.normalizeOptional(request.requesterMessage());
		return mentorshipRequest;
	}

	public void recordDecision(MentorshipRequestStatus decision, String message) {
		status = decision;
		if (decision == MentorshipRequestStatus.DECLINED) {
			rejectionReason = TextUtils.trim(message);
		} else {
			mentorMessage = TextUtils.trim(message);
		}
	}

	public void markReplied() {
		status = MentorshipRequestStatus.PENDING;
	}

	public void withdraw() {
		status = MentorshipRequestStatus.WITHDRAWN;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requester_id", nullable = false)
	private AppUser requester;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentor_id", nullable = false)
	private AppUser mentor;

	@Column(name = "requested_for_name", nullable = false, columnDefinition = "TEXT")
	private String requestedForName;

	@Column(name = "support_area", nullable = false, columnDefinition = "TEXT")
	private String supportArea;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MentorshipRequestUrgency urgency;

	@Enumerated(EnumType.STRING)
	@Column(name = "mentorship_format", nullable = false)
	private MentorshipRequestFormat mentorshipFormat;

	@Enumerated(EnumType.STRING)
	@Column(name = "meeting_mode", nullable = false)
	private MeetingMode meetingMode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MentorshipRequestStatus status;

	@Column(name = "requester_message", columnDefinition = "TEXT")
	private String requesterMessage;

	@Column(name = "mentor_message", columnDefinition = "TEXT")
	private String mentorMessage;

	@Column(name = "rejection_reason", columnDefinition = "TEXT")
	private String rejectionReason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
