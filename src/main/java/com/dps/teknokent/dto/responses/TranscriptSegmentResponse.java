package com.dps.teknokent.dto.responses;

public record TranscriptSegmentResponse(
    String id,
    String speaker,
    String text,
    String at
) {}