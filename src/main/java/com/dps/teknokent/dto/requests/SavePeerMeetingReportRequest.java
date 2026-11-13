package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActionOwner;
import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingTopic;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A meeting report draft as the mentor saved it; every field may still be empty. Sending checks the required ones.
 * The meeting, mentee and program come from the meeting itself, never from the body.
 */
public record SavePeerMeetingReportRequest(
		UUID actorId,
		PeerMeetingType meetingType,
		PeerMeetingFormat meetingFormat,
		@Min(1) @Max(600) Integer actualDurationMinutes,
		@Size(max = 8) Set<@NotNull PeerMeetingTopic> topics,
		@Size(max = 4000) String menteeNeed,
		@Size(max = 4000) String discussion,
		@Size(max = 4000) String approach,
		@Size(max = 10) Set<@NotNull PeerMentorSkill> skills,
		@Size(max = 4000) String skillExample,
		@Size(max = 4000) String outcome,
		@Size(max = 20) List<@Valid @NotNull Action> actions,
		boolean followUpNeeded,
		Instant followUpAt,
		@Min(1) @Max(5) Integer productivity,
		@Size(max = 4000) String nextTime,
		Boolean coordinatorSupportNeeded) {

	/** An action step; {@code id} keeps an existing one, null adds a new one. */
	public record Action(
			UUID id,
			@NotBlank @Size(max = 500) String text,
			@NotNull PeerActionOwner owner,
			LocalDate dueDate,
			boolean done) {
	}
}
