package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActionOwner;

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

/** One "eylem adımı" of a meeting report: who does what by when, and whether it is done. */
@Entity
@Table(name = "peer_meeting_report_action")
@Getter
@Setter
@NoArgsConstructor
public class PeerMeetingReportAction {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false)
	private PeerMeetingReport report;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false, length = 500)
	private String text;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PeerActionOwner owner;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "done_at")
	private Instant doneAt;
}
