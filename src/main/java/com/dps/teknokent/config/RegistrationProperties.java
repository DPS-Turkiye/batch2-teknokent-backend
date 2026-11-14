package com.dps.teknokent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Who may create an account on this deployment. An empty domain list lets any address sign up. */
@ConfigurationProperties(prefix = "app.registration")
public record RegistrationProperties(List<String> allowedEmailDomains) {

	public RegistrationProperties {
		allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
	}
}
