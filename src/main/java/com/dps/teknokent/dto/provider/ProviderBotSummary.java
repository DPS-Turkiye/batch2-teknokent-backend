package com.dps.teknokent.dto.provider;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One row of the provider's bot list (Vexa `GET /bots`), newest first. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderBotSummary(
    Long id,
    String platform,
    @JsonProperty("native_meeting_id") String nativeMeetingId,
    String status,
    @JsonProperty("completion_reason") String completionReason,
    @JsonProperty("created_at") Instant createdAt
) {}
