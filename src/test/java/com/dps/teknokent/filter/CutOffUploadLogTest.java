package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dps.teknokent.security.FirebaseIdentity;
import com.dps.teknokent.security.FirebaseTokenVerifier;
import com.dps.teknokent.service.ProfileAnalysisService;

/**
 * A real server and a raw socket, because the case is the transport itself: the caller announces a file and
 * then stops sending it. MockMvc always hands over a complete body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CutOffUploadLogTest {

	private static final int ANNOUNCED_BYTES = 58_000;

	@Value("${local.server.port}")
	private int port;

	@MockitoBean
	private FirebaseTokenVerifier firebaseTokenVerifier;

	@MockitoBean
	private ProfileAnalysisService profileAnalysisService;

	@Test
	void anUploadThatStopsArrivingIsLoggedWithHowMuchWasAnnounced(CapturedOutput output) throws Exception {
		when(firebaseTokenVerifier.enabled()).thenReturn(true);
		when(firebaseTokenVerifier.verify("token-of-someone-signing-up"))
				.thenReturn(Optional.of(new FirebaseIdentity("uid-signing-up", "signing.up@example.com", false)));

		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
			OutputStream out = socket.getOutputStream();
			out.write(("""
					POST /api/profile-analyses/preview?consentAccepted=true&consentTextVersion=cv-1 HTTP/1.1\r
					Host: localhost\r
					Authorization: Bearer token-of-someone-signing-up\r
					Content-Type: multipart/form-data; boundary=cut\r
					Content-Length: %d\r
					\r
					--cut\r
					Content-Disposition: form-data; name="file"; filename="cv.pdf"\r
					Content-Type: application/pdf\r
					\r
					%%PDF-1.4 and then nothing more""".formatted(ANNOUNCED_BYTES)).getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
		}

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(output)
				.contains("Request body cut off: POST /api/profile-analyses/preview -> 400")
				.contains(ANNOUNCED_BYTES + " bytes announced"));
		assertThat(output).doesNotContain("consentTextVersion").doesNotContain("token-of-someone-signing-up");
		verify(profileAnalysisService, never()).parsePreview(any(), any(), any(), any());
	}
}
