package com.dps.teknokent.security;

import java.util.List;

/** What office hours keep from a verified LTI 1.3 resource-link launch. */
public record LtiLaunchClaims(
		String issuer,
		String subject,
		String fullName,
		String email,
		String contextId,
		String contextLabel,
		String contextTitle,
		List<String> roles) {
}
