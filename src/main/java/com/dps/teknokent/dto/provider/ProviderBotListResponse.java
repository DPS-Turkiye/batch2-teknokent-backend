package com.dps.teknokent.dto.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderBotListResponse(List<ProviderBotSummary> meetings) {}
