package com.dps.teknokent.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import com.dps.teknokent.domain.MeetingFrequency;
import com.dps.teknokent.domain.MentorLanguage;
import com.dps.teknokent.domain.PrepFileRequirement;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mentor_profile")
@Getter
@Setter
@NoArgsConstructor
public class MentorProfile {

	@Id
	@Column(name = "user_id")
	private UUID id;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private AppUser user;

	@Column(nullable = false, length = 8)
	private String initials;

	@Column(name = "professional_title", nullable = false)
	private String professionalTitle;

	@Column(nullable = false)
	private String company;

	@Column(name = "availability_label", nullable = false)
	private String availabilityLabel;

	@Column(name = "response_time_label", nullable = false)
	private String responseTimeLabel;

	@Column(name = "meeting_mode", nullable = false)
	private String meetingMode;

	@Column(name = "communication_preference", nullable = false)
	private String communicationPreference;

	@Column(name = "mentoring_style", nullable = false)
	private String mentoringStyle;

	@Column(name = "fit_label", nullable = false)
	private String fitLabel;

	@Column(name = "experience_level", nullable = false)
	private String experienceLevel;

	@Column(name = "sector_experience", nullable = false)
	private String sectorExperience;

	@Column(name = "startup_stages", nullable = false)
	private String startupStages;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "mentee_capacity")
	private Integer menteeCapacity;

	@Column(name = "about", nullable = false, columnDefinition = "TEXT")
	private String about = "";

	@Column(name = "default_meeting_duration_minutes", nullable = false)
	private int defaultMeetingDurationMinutes = 45;

	@Column(name = "meeting_buffer_minutes", nullable = false)
	private int meetingBufferMinutes = 15;

	@Enumerated(EnumType.STRING)
	@Column(name = "meeting_frequency", length = 32)
	private MeetingFrequency meetingFrequency;

	@Enumerated(EnumType.STRING)
	@Column(name = "prep_file_requirement", length = 32)
	private PrepFileRequirement prepFileRequirement;

	@ElementCollection
	@CollectionTable(name = "mentor_language", joinColumns = @JoinColumn(name = "mentor_id"))
	@OrderColumn(name = "display_order")
	@Enumerated(EnumType.STRING)
	@Column(name = "language", nullable = false, length = 32)
	@BatchSize(size = 50)
	private List<MentorLanguage> languages = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "mentor_specialty", joinColumns = @JoinColumn(name = "mentor_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "specialty", nullable = false)
	@BatchSize(size = 50)
	private List<String> specialties = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "mentor_match_reason", joinColumns = @JoinColumn(name = "mentor_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "reason", nullable = false, length = 500)
	@BatchSize(size = 50)
	private List<String> recommendationReasons = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "mentor_profile_evidence", joinColumns = @JoinColumn(name = "mentor_id"))
	@OrderColumn(name = "display_order")
	@BatchSize(size = 50)
	private List<MentorProfileEvidence> confirmedEvidence = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "mentor_experience", joinColumns = @JoinColumn(name = "mentor_id"))
	@OrderColumn(name = "display_order")
	@BatchSize(size = 50)
	private List<MentorExperience> experiences = new ArrayList<>();
}
