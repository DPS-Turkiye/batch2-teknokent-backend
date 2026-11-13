package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingTopic;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** "Görüşme Raporu": what a peer mentor writes after one meeting, about one mentee. Never shown to the mentee. */
@Entity
@Table(name = "peer_meeting_report")
@Getter
@Setter
@NoArgsConstructor
public class PeerMeetingReport extends PeerReportReview {

	@Id
	private UUID id;

	@Column(name = "meeting_id", nullable = false, updatable = false)
	private UUID meetingId;

	@Column(name = "mentor_id", nullable = false, updatable = false)
	private UUID mentorId;

	@Column(name = "mentee_id", nullable = false, updatable = false)
	private UUID menteeId;

	@Column(name = "program_id")
	private UUID programId;

	@Enumerated(EnumType.STRING)
	@Column(name = "meeting_type", length = 16)
	private PeerMeetingType meetingType;

	@Enumerated(EnumType.STRING)
	@Column(name = "meeting_format", length = 16)
	private PeerMeetingFormat meetingFormat;

	@Column(name = "actual_duration_minutes")
	private Integer actualDurationMinutes;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "peer_meeting_report_topic", joinColumns = @JoinColumn(name = "report_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "topic", length = 40, nullable = false)
	private Set<PeerMeetingTopic> topics = EnumSet.noneOf(PeerMeetingTopic.class);

	@Column(name = "mentee_need", columnDefinition = "TEXT")
	private String menteeNeed;

	@Column(columnDefinition = "TEXT")
	private String discussion;

	@Column(columnDefinition = "TEXT")
	private String approach;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "peer_meeting_report_skill", joinColumns = @JoinColumn(name = "report_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "skill", length = 40, nullable = false)
	private Set<PeerMentorSkill> skills = EnumSet.noneOf(PeerMentorSkill.class);

	@Column(name = "skill_example", columnDefinition = "TEXT")
	private String skillExample;

	@Column(columnDefinition = "TEXT")
	private String outcome;

	@OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position ASC")
	private List<PeerMeetingReportAction> actions = new ArrayList<>();

	@Column(name = "follow_up_needed", nullable = false)
	private boolean followUpNeeded;

	@Column(name = "follow_up_at")
	private Instant followUpAt;

	private Integer productivity;

	@Column(name = "next_time", columnDefinition = "TEXT")
	private String nextTime;

	/** Yes/no only: the frame forbids writing why. */
	@Column(name = "coordinator_support_needed")
	private Boolean coordinatorSupportNeeded;
}
