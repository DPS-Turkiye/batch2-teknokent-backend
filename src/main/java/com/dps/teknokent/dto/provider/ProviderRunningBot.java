package com.dps.teknokent.dto.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProviderRunningBot(
    String platform,
    @JsonProperty("native_meeting_id") String nativeMeetingId,
    String status
) {}
