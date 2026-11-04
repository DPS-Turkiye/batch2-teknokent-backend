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
		mentorshipRequest.expectedOutcome = TextUtils.normalizeOptional(request.expectedOutcome());
		return mentorshipRequest;
	}

	public static MentorshipRequest forCalendarMeeting(
			AppUser requester,
			Organization organization,
			AppUser mentor,
			String agenda) {
		MentorshipRequest mentorshipRequest = new MentorshipRequest();
		mentorshipRequest.requester = requester;
		mentorshipRequest.organization = organization;
		mentorshipRequest.mentor = mentor;
		mentorshipRequest.requestedForName = requester.getFullName();
		String topic = TextUtils.normalizeOptional(agenda);
		mentorshipRequest.supportArea = topic == null ? "Takvim toplantısı" : topic;
		mentorshipRequest.summary = mentorshipRequest.supportArea;
		mentorshipRequest.urgency = MentorshipRequestUrgency.THIS_WEEK;
		mentorshipRequest.mentorshipFormat = MentorshipRequestFormat.SINGLE_MEETING;
		mentorshipRequest.meetingMode = MeetingMode.ONLINE;
		mentorshipRequest.status = MentorshipRequestStatus.ACCEPTED;
		mentorshipRequest.mentorMessage = "Takvimden oluşturuldu";
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

	/** The mentee's program when this began; null for a startup outside any program. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "program_id")
	private Program program;

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

	@Column(name = "expected_outcome", columnDefinition = "TEXT")
	private String expectedOutcome;

	@Column(name = "mentor_message", columnDefinition = "TEXT")
	private String mentorMessage;

	@Column(name = "rejection_reason", columnDefinition = "TEXT")
	private String rejectionReason;

	@Column(name = "planned_session_count")
	private Integer plannedSessionCount;

	public void planSessions(Integer plannedSessionCount) {
		this.plannedSessionCount = plannedSessionCount;
	}

	@Column(name = "mentor_read_at")
	private Instant mentorReadAt;

	@Column(name = "mentee_read_at")
	private Instant menteeReadAt;

	public void markReadBy(AppUser reader, Instant at) {
		if (reader.getId().equals(mentor.getId())) {
			mentorReadAt = at;
		} else {
			menteeReadAt = at;
		}
	}

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
