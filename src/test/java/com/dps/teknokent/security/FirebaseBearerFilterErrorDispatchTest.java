package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.service.WeeklyProgramService;

/**
 * A real server, because the case is the servlet container's error dispatch: an exception no handler maps is
 * forwarded to /error, which MockMvc never does. The weekly program read is the path a coordinator's own token
 * reaches, and the one that failed in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirebaseBearerFilterErrorDispatchTest {

	private static final String TOKEN = "token-of-a-coordinator";
	private static final String FAILURE = "not mapped by any handler";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private AppUserRepository appUserRepository;
	@MockitoBean
	private FirebaseTokenVerifier firebaseTokenVerifier;
	@MockitoBean
	private WeeklyProgramService weeklyProgramService;

	private final HttpClient client = HttpClient.newHttpClient();
	private final UUID programId = UUID.randomUUID();
	// Unique per run: the server commits on its own thread, so a row left by a failed run must not collide.
	private final String uid = "uid-error-dispatch-" + UUID.randomUUID();
	private AppUser coordinator;

	@BeforeEach
	void setUp() {
		coordinator = new AppUser();
		coordinator.setEmail(uid + "@teknokent-dev.invalid");
		coordinator.setFullName("Deniz Örnek");
		coordinator.setRole(UserRole.COORDINATOR);
		coordinator.setFirebaseUid(uid);
		coordinator = appUserRepository.save(coordinator);
	}

	@AfterEach
	void tearDown() {
		appUserRepository.delete(coordinator);
	}

	@Test
	void anUnhandledExceptionOnASignedInRequestIsA500NotASignOut() throws Exception {
		signedIn();
		when(weeklyProgramService.read(eq(coordinator.getId()), eq(programId), any()))
				.thenThrow(new IllegalStateException(FAILURE));

		HttpResponse<String> response = read("Bearer " + TOKEN);

		assertThat(response.statusCode()).isEqualTo(500);
		// The container's error page for the request's own path, saying nothing about the exception.
		assertThat(response.body()).contains("/weekly-program/read").doesNotContain(FAILURE)
				.doesNotContain("IllegalStateException");
	}

	@Test
	void aRequestWithoutCredentialsIsStillRefusedWith401() throws Exception {
		assertThat(read(null).statusCode()).isEqualTo(401);
	}

	@Test
	void theErrorPageCannotBeOpenedDirectly() throws Exception {
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).timeout(TIMEOUT).GET()
						.build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void aSignedInCallerCannotOpenTheErrorPageDirectlyEither() throws Exception {
		signedIn();

		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).timeout(TIMEOUT)
						.header("Authorization", "Bearer " + TOKEN).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		// A token only opens the paths the browser calls; /error is not one of them.
		assertThat(response.statusCode()).isEqualTo(403);
	}

	private void signedIn() {
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify(TOKEN))
				.thenReturn(Optional.of(new FirebaseIdentity(uid, coordinator.getEmail(), true)));
	}

	private HttpResponse<String> read(String authorization) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + "/api/coordinator/programs/" + programId
						+ "/weekly-program/read"))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"documentId\": \"" + UUID.randomUUID() + "\"}"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
