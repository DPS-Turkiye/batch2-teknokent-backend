package com.dps.teknokent.dto.responses;

public record AddBotResponse(
    String id,
    String assistantSessionId,
    String platform,
    String status,
    String title
) {}
