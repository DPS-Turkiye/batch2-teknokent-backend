package com.dps.teknokent.security;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AppLanguage;

/** What office hours keep from a verified LTI 1.3 resource-link launch. */
public record LtiLaunchClaims(
		String issuer,
		String subject,
		String fullName,
		@Nullable String email,
		String contextId,
		@Nullable String contextLabel,
		@Nullable String contextTitle,
		List<String> roles,
		@Nullable AppLanguage language) {
}
