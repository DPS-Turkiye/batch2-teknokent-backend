package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;

public record AddBotRequest(
    @NotBlank String meetingUrl
) {}