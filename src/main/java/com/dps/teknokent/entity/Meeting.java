package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
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
@Table(name = "meeting")
@Getter
@Setter
@NoArgsConstructor
public class Meeting {

	public static Meeting fromRequest(CreateMeetingRequest request, Organization organization, AppUser mentor) {
		Meeting meeting = new Meeting();
		meeting.organization = organization;
		meeting.mentor = mentor;
		meeting.scheduledAt = request.scheduledAt();
		meeting.durationMinutes = request.durationMinutes();
		meeting.agenda = TextUtils.normalizeOptional(request.agenda());
		meeting.notes = TextUtils.normalizeOptional(request.notes());
		meeting.outcomes = TextUtils.normalizeOptional(request.outcomes());
		meeting.meetLink = TextUtils.normalizeOptional(request.meetLink());
		meeting.status = request.status();
		return meeting;
	}

	public static Meeting forMentorshipRequest(MentorshipRequest request, MentorAvailabilitySlot slot) {
		Meeting meeting = new Meeting();
		meeting.mentorshipRequest = request;
		meeting.organization = request.getOrganization();
		meeting.mentor = request.getMentor();
		meeting.scheduledAt = slot.getStartsAt();
		meeting.durationMinutes = slot.getDurationMinutes();
		meeting.agenda = request.getSupportArea() + " — Mentorluk Görüşmesi";
		meeting.status = MeetingStatus.SCHEDULED;
		return meeting;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mentor_id")
	private AppUser mentor;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mentorship_request_id")
	private MentorshipRequest mentorshipRequest;

	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	@Column(name = "duration_minutes", nullable = false)
	private Integer durationMinutes;

	@Column(columnDefinition = "TEXT")
	private String agenda;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(columnDefinition = "TEXT")
	private String outcomes;

	@Column(name = "meet_link", length = 2048)
	private String meetLink;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MeetingStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
