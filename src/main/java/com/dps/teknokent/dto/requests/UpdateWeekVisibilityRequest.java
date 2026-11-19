package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.WeekVisibility;

import jakarta.validation.constraints.NotNull;

/** Who a saved week of the plan is shown to: AUTO (once it begins), SHOWN (now) or HIDDEN. */
public record UpdateWeekVisibilityRequest(UUID actorId, @NotNull WeekVisibility visibility) {
}
