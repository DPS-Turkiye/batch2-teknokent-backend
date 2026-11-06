package com.dps.teknokent.dto.responses;

public record PlatformFeedbackInboxResponse(PageResponse<PlatformFeedbackResponse> page, long unreadCount) {
}
