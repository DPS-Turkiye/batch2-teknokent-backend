package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.domain.StartupStageState;

public record StartupStageResponse(StartupStage stage, StartupStageState state) {
}
