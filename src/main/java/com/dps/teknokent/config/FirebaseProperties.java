package com.dps.teknokent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.firebase")
public record FirebaseProperties(
		String projectId,
		String credentialsJson) {

	public boolean enabled() {
		return credentialsJson != null && !credentialsJson.isBlank();
	}
}
