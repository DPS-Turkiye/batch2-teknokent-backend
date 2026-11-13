package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.RequestDispatcher;

@ExtendWith(OutputCaptureExtension.class)
class SlowRequestLogFilterTest {

	@Test
	void logsARequestAtOrOverTheThresholdWithItsPathButNotItsQuery(CapturedOutput output) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/overview");
		request.setQueryString("actorId=secret-id");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(200);

		new SlowRequestLogFilter(0).doFilter(request, response, new MockFilterChain());

		assertThat(output).contains("Slow request: GET /api/admin/overview -> 200 in");
		assertThat(output).doesNotContain("secret-id");
	}

	@Test
	void staysQuietForAFastRequest(CapturedOutput output) throws Exception {
		new SlowRequestLogFilter(60_000).doFilter(
				new MockHttpServletRequest("GET", "/api/health"), new MockHttpServletResponse(), new MockFilterChain());

		assertThat(output).doesNotContain("Slow request");
	}

	@Test
	void namesARequestWhoseBodyStoppedArrivingHoweverFastItFailed(CapturedOutput output) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/profile-analyses/preview");
		request.setQueryString("consentAccepted=true");
		request.setContent(new byte[1234]);
		// What Tomcat leaves behind when it gives up reading a request body.
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new EOFException());
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(400);

		new SlowRequestLogFilter(60_000).doFilter(request, response, new MockFilterChain());

		assertThat(output)
				.contains("Request body cut off: POST /api/profile-analyses/preview -> 400 after")
				.contains("(1234 bytes announced, EOFException)");
		assertThat(output).doesNotContain("Slow request").doesNotContain("consentAccepted");
	}

	@Test
	void aRefusedRequestThatWasReadInFullIsNotCalledCutOff(CapturedOutput output) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/profile-analyses/preview");
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("not a read failure"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(400);

		new SlowRequestLogFilter(60_000).doFilter(request, response, new MockFilterChain());

		assertThat(output).doesNotContain("Request body cut off");
	}

	@Test
	void saysSoWhenTheCutOffBodyNeverAnnouncedItsLength(CapturedOutput output) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/documents");
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new EOFException());
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(400);

		new SlowRequestLogFilter(60_000).doFilter(request, response, new MockFilterChain());

		assertThat(output).contains("(length not announced, EOFException)");
	}
}
