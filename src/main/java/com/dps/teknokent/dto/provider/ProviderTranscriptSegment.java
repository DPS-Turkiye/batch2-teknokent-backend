package com.dps.teknokent.dto.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProviderTranscriptSegment(
    @JsonProperty("segment_id") String segmentId,
    String speaker,
    String text,
    Double start
) {}