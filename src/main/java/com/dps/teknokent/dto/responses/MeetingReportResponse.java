package com.dps.teknokent.dto.responses;

import java.util.List;

public record MeetingReportResponse(
    String title,
    String meetingDate,
    String topic,
    String summary,
    List<String> topicsDiscussed,
    List<String> decisions,
    List<MeetingReportActionItem> actionItems,
    List<String> risksOrBlockers,
    List<String> nextSteps,
    String model
) {}
