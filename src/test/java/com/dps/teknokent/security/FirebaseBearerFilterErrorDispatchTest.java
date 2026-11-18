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
	private static final String UID = "uid-error-dispatch";

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
	private AppUser coordinator;

	@BeforeEach
	void setUp() {
		coordinator = new AppUser();
		coordinator.setEmail("error-dispatch@teknokent-dev.invalid");
		coordinator.setFullName("Deniz Örnek");
		coordinator.setRole(UserRole.COORDINATOR);
		coordinator.setFirebaseUid(UID);
		coordinator = appUserRepository.save(coordinator);
	}

	@AfterEach
	void tearDown() {
		appUserRepository.delete(coordinator);
	}

	@Test
	void anUnhandledExceptionOnASignedInRequestIsA500NotASignOut() throws Exception {
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify(TOKEN))
				.thenReturn(Optional.of(new FirebaseIdentity(UID, coordinator.getEmail(), true)));
		when(weeklyProgramService.read(eq(coordinator.getId()), eq(programId), any()))
				.thenThrow(new IllegalStateException("not mapped by any handler"));

		HttpResponse<String> response = read("Bearer " + TOKEN);

		assertThat(response.statusCode()).isEqualTo(500);
	}

	@Test
	void aRequestWithoutCredentialsIsStillRefusedWith401() throws Exception {
		assertThat(read(null).statusCode()).isEqualTo(401);
	}

	@Test
	void theErrorPageCannotBeOpenedDirectly() throws Exception {
		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void aSignedInCallerCannotOpenTheErrorPageDirectlyEither() throws Exception {
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify(TOKEN))
				.thenReturn(Optional.of(new FirebaseIdentity(UID, coordinator.getEmail(), true)));

		HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error"))
						.header("Authorization", "Bearer " + TOKEN).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(403);
	}

	private HttpResponse<String> read(String authorization) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + "/api/coordinator/programs/" + programId
						+ "/weekly-program/read"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"documentId\": \"" + UUID.randomUUID() + "\"}"));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
