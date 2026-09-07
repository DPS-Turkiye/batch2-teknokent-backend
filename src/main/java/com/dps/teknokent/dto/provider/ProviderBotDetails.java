package com.dps.teknokent.dto.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderBotDetails(
    String status,
    String platform,
    @JsonProperty("native_meeting_id") String nativeMeetingId,
    List<Object> participants,
    List<Object> attendees
) {}
