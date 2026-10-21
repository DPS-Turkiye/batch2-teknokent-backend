package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
}
