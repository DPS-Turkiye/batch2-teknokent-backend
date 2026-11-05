package com.dps.teknokent.dto.requests;

import java.util.Map;
import jakarta.validation.constraints.NotNull;

public record CreateStartupAnalysisRecordRequest(@NotNull Map<String, Object> profile) {}
