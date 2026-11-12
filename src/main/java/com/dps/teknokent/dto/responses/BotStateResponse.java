package com.dps.teknokent.dto.responses;

import java.time.Instant;

import com.dps.teknokent.domain.MeetingBotEndReason;
import com.dps.teknokent.domain.MeetingBotState;

/**
 * The latest recording bot of one meeting link. {@code endReason} is set once the bot's run has ended;
 * {@code requestedAt} lets the caller tell how long the bot has waited to be admitted.
 */
public record BotStateResponse(
    String platform,
    String nativeMeetingId,
    MeetingBotState state,
    MeetingBotEndReason endReason,
    Instant requestedAt
) {}
