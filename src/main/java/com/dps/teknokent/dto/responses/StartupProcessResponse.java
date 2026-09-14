package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

/**
 * Everything the Süreç tab draws in one read: the stage tracker, the focus
 * round (null when none is planned) and every Ödev, oldest first.
 */
public record StartupProcessResponse(
		UUID organizationId,
		StartupStage currentStage,
		List<StartupStageResponse> stages,
		StartupRoundResponse currentRound,
		List<StartupRoundResponse> rounds,
		List<ProcessAssignmentResponse> assignments) {
}
