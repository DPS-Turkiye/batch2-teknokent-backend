package com.dps.teknokent.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.dps.teknokent.config.FirebaseProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {

	private static final String APP_NAME = "teknokent-auth";

	private final FirebaseAuth firebaseAuth;

	public FirebaseAdminTokenVerifier(FirebaseProperties properties) {
		this.firebaseAuth = FirebaseAuth.getInstance(firebaseApp(properties));
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Optional<FirebaseIdentity> verify(String idToken) {
		try {
			FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken);
			String email = decoded.getEmail();
			if (email == null || email.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(new FirebaseIdentity(decoded.getUid(), email));
		} catch (FirebaseAuthException | IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static FirebaseApp firebaseApp(FirebaseProperties properties) {
		return FirebaseApp.getApps().stream()
				.filter(app -> APP_NAME.equals(app.getName()))
				.findFirst()
				.orElseGet(() -> FirebaseApp.initializeApp(options(properties), APP_NAME));
	}

	private static FirebaseOptions options(FirebaseProperties properties) {
		try (ByteArrayInputStream credentials = new ByteArrayInputStream(
				properties.credentialsJson().getBytes(StandardCharsets.UTF_8))) {
			FirebaseOptions.Builder builder = FirebaseOptions.builder()
					.setCredentials(GoogleCredentials.fromStream(credentials));
			if (properties.projectId() != null && !properties.projectId().isBlank()) {
				builder.setProjectId(properties.projectId());
			}
			return builder.build();
		} catch (IOException exception) {
			throw new IllegalStateException("FIREBASE_CREDENTIALS_JSON is not a valid service account key", exception);
		}
	}
}
