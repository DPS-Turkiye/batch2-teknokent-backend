package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

/**
 * Everything the Süreç tab draws in one read: the phases of the team's program (empty without a program or
 * phases), the focus round (null when none is planned) and every Ödev, oldest first. {@code currentPhaseId} is the
 * phase today falls in, or null. {@code currentStage} and {@code stages} are the retired fixed stage list, still
 * sent for one release.
 */
public record StartupProcessResponse(
		UUID organizationId,
		StartupStage currentStage,
		List<StartupStageResponse> stages,
		StartupRoundResponse currentRound,
		List<StartupRoundResponse> rounds,
		List<ProcessAssignmentResponse> assignments,
		UUID currentPhaseId,
		List<ProcessPhaseResponse> phases) {
}
