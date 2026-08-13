package com.dps.teknokent.dto.responses;

import java.util.List;

public record TranscriptResponse(
    List<TranscriptSegmentResponse> segments
) {}