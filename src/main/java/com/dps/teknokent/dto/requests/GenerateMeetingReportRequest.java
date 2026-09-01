package com.dps.teknokent.dto.requests;

import java.util.List;

import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;

public record GenerateMeetingReportRequest(
    String model,
    List<TranscriptSegmentResponse> segments
) {}
