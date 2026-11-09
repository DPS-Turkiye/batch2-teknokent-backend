package com.dps.teknokent.config;

import java.net.MalformedURLException;
import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dps.teknokent.security.LtiPlatformKeys;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;

@Configuration
@EnableConfigurationProperties(LtiProperties.class)
public class LtiConfig {

	/** Moodle's keys, fetched on first use and cached; a rotated key is fetched again when a token names it. */
	@Bean
	LtiPlatformKeys ltiPlatformKeys(LtiProperties properties) throws MalformedURLException {
		if (!properties.configured()) {
			return LtiPlatformKeys.unavailable();
		}
		return LtiPlatformKeys.of(JWKSourceBuilder.create(URI.create(properties.platformJwksUrl()).toURL())
				.retrying(true)
				.build());
	}
}
