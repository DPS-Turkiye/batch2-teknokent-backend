package com.dps.teknokent.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.dps.teknokent.service.GoogleMeetClient;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import tools.jackson.databind.json.JsonMapper;

/** Meet links made by Menton's own Google Workspace account. Nothing here exists unless a deployment turns it on. */
@Configuration
@ConditionalOnProperty(prefix = "app.google-meet", name = "enabled", havingValue = "true")
public class GoogleMeetConfig {

	/** Only meetings this account made: it can neither list nor change anybody else's. */
	private static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/meetings.space.created");

	@Bean
	GoogleMeetClient googleMeetClient(
			@Value("${app.google-meet.base-url}") String baseUrl,
			@Value("${app.google-meet.credentials-json}") String credentialsJson,
			@Value("${app.google-meet.user}") String user,
			@Value("${app.google-meet.access-type}") String accessType,
			JsonMapper jsonMapper) {
		GoogleCredentials credentials = credentials(credentialsJson, user);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		RestClient client = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).build();
		return new GoogleMeetClient(client, jsonMapper, () -> accessToken(credentials), accessType);
	}

	/** The service account acting as the Workspace user, which its admin allowed for this one scope. */
	static GoogleCredentials credentials(String json, String user) {
		if (json.isBlank() || user.isBlank()) {
			throw new IllegalStateException("GOOGLE_MEET_CREDENTIALS_JSON and GOOGLE_MEET_USER are required");
		}
		try (ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			return ServiceAccountCredentials.fromStream(in).createScoped(SCOPES).createDelegated(user.trim());
		} catch (IOException invalid) {
			// The cause could quote the key, so it is left out.
			throw new IllegalStateException("GOOGLE_MEET_CREDENTIALS_JSON is not a valid service account key");
		}
	}

	private static String accessToken(GoogleCredentials credentials) {
		try {
			credentials.refreshIfExpired();
		} catch (IOException refused) {
			throw new IllegalStateException("Google refused the Meet account's sign-in: " + refused.getMessage());
		}
		AccessToken token = credentials.getAccessToken();
		if (token == null) {
			throw new IllegalStateException("Google gave the Meet account no access token");
		}
		return token.getTokenValue();
	}
}
