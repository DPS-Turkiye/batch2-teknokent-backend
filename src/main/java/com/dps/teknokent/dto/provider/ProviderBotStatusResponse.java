package com.dps.teknokent.dto.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProviderBotStatusResponse(
    @JsonProperty("running_bots") List<ProviderRunningBot> runningBots
) {}
