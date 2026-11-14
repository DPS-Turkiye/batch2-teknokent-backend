package com.dps.teknokent.dto.responses;

import org.jspecify.annotations.Nullable;

public record MeetingReportActionItem(
		@Nullable String owner,
		String task,
		@Nullable String due) {
}
