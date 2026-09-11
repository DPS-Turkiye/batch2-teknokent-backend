package com.dps.teknokent.domain;

import com.dps.teknokent.exception.InvalidRequestException;

public enum MentorshipWorkspaceTab {
	ACTIVE,
	REQUESTS,
	HISTORY;

	public static MentorshipWorkspaceTab fromParam(String value) {
		if (value == null || value.isBlank()) {
			throw new InvalidRequestException("tab is required");
		}
		try {
			return MentorshipWorkspaceTab.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException exception) {
			throw new InvalidRequestException(
					"tab must be one of: active, requests, history");
		}
	}
}
