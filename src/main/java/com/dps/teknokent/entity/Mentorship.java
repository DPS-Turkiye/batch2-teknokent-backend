package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
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
@Table(name = "mentorship")
@Getter
@Setter
@NoArgsConstructor
public class Mentorship {

	public static Mentorship fromRequest(
			CreateMentorshipRequest request, AppUser mentor, Organization organization) {
		Mentorship mentorship = new Mentorship();
		mentorship.mentor = mentor;
		mentorship.organization = organization;
		mentorship.status = request.status();
		mentorship.startDate = request.startDate();
		mentorship.endDate = request.endDate();
		mentorship.daysPerWeek = request.daysPerWeek() == null ? 1 : request.daysPerWeek();
		mentorship.goals = TextUtils.normalizeOptional(request.goals());
		return mentorship;
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentor_id", nullable = false)
	private AppUser mentor;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	/** The mentee's program when this began; null for a startup outside any program. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "program_id")
	private Program program;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MentorshipStatus status;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Column(name = "days_per_week", nullable = false)
	private int daysPerWeek = 1;

	@Column(columnDefinition = "TEXT")
	private String goals;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
