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
		meeting.transcribeRequested = Boolean.TRUE.equals(request.transcribeRequested());
		meeting.status = request.status();
		return meeting;
	}

	public Meeting copyFor(Organization guestOrganization, AppUser guestMentor) {
		Meeting copy = new Meeting();
		copy.organization = guestOrganization;
		copy.mentor = guestMentor;
		copy.scheduledAt = scheduledAt;
		copy.durationMinutes = durationMinutes;
		copy.agenda = agenda;
		copy.notes = notes;
		copy.outcomes = outcomes;
		copy.meetLink = meetLink;
		copy.transcribeRequested = transcribeRequested;
		copy.botDispatchedAt = botDispatchedAt;
		copy.status = status;
		return copy;
	}

	public static Meeting forLoggedEntry(
			MentorshipRequest request,
			Instant scheduledAt,
			Integer durationMinutes,
			String agenda,
			String notes,
			String outcomes,
			MeetingStatus status,
			String meetLink) {
		Meeting meeting = new Meeting();
		meeting.mentorshipRequest = request;
		meeting.organization = request.getOrganization();
		meeting.mentor = request.getMentor();
		meeting.scheduledAt = scheduledAt;
		meeting.durationMinutes = durationMinutes;
		meeting.agenda = TextUtils.normalizeOptional(agenda);
		meeting.notes = TextUtils.normalizeOptional(notes);
		meeting.outcomes = TextUtils.normalizeOptional(outcomes);
		meeting.meetLink = TextUtils.normalizeOptional(meetLink);
		meeting.status = status;
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

	@Column(name = "transcribe_requested", nullable = false)
	private boolean transcribeRequested;

	@Column(name = "bot_dispatched_at")
	private Instant botDispatchedAt;

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
