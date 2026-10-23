package com.dps.teknokent.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public final class FirebaseApps {

	private static final String APP_NAME = "teknokent-auth";

	private FirebaseApps() {
	}

	public static synchronized FirebaseApp named(FirebaseProperties properties) {
		return FirebaseApp.getApps().stream()
				.filter(app -> APP_NAME.equals(app.getName()))
				.findFirst()
				.orElseGet(() -> FirebaseApp.initializeApp(options(properties), APP_NAME));
	}

	private static FirebaseOptions options(FirebaseProperties properties) {
		try (ByteArrayInputStream credentials = new ByteArrayInputStream(
				properties.credentialsJson().getBytes(StandardCharsets.UTF_8))) {
			// The SDK's default is no timeout, and every bearer call can now wait on Firebase.
			FirebaseOptions.Builder builder = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(credentials))
					.setConnectTimeout(3_000)
					.setReadTimeout(5_000);
			if (properties.projectId() != null && !properties.projectId().isBlank()) {
				builder.setProjectId(properties.projectId());
			}
			return builder.build();
		} catch (IOException exception) {
			throw new IllegalStateException("FIREBASE_CREDENTIALS_JSON is not a valid service account key", exception);
		}
	}
}
