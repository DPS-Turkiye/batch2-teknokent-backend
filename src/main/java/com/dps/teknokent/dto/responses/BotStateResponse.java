package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.MeetingBotEndReason;
import com.dps.teknokent.domain.MeetingBotState;

/** The latest recording bot of one meeting link. {@code endReason} is set once the bot's run has ended. */
public record BotStateResponse(
    String platform,
    String nativeMeetingId,
    MeetingBotState state,
    MeetingBotEndReason endReason
) {}
