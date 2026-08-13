package com.dps.teknokent.dto.responses;

public record AddBotResponse(
    String id,
    String platform,
    String nativeMeetingId,
    String status,
    String title
) {}