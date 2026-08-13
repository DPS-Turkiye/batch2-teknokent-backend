package com.dps.teknokent.dto.provider;

import java.util.List;

public record ProviderTranscriptResponse(
    List<ProviderTranscriptSegment> segments
) {}