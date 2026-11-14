package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;

class RateLimitingFilterTest {

	private static final String CV_PARSE = "/api/profile-analyses";
	private static final String ASSISTANT = "/api/assistant/chat";

	private final RateLimitingFilter filter = new RateLimitingFilter(new RateLimitingConfig());

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theAssistantAllowsAHundredCallsAMinutePerAddressAndCountsThemDown() throws Exception {
		assertThat(call(from("GET", ASSISTANT, "198.51.100.20")).getHeader("X-Rate-Limit-Remaining")).isEqualTo("99");
		for (int i = 0; i < 99; i++) {
			assertThat(call(from("POST", ASSISTANT, "198.51.100.20")).getStatus()).isEqualTo(200);
		}

		MockHttpServletResponse refused = call(from("POST", ASSISTANT, "198.51.100.20"));

		assertThat(refused.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(refused.getHeader(RateLimitingFilter.RETRY_AFTER_HEADER))).isBetween(1L, 60L);
		assertThat(refused.getErrorMessage()).contains("Too many requests");
		assertThat(call(from("POST", ASSISTANT, "198.51.100.21")).getStatus()).isEqualTo(200);
	}

	@Test
	void aRefusedCallNeverReachesTheApplication() throws Exception {
		for (int i = 0; i < 100; i++) {
			call(from("POST", ASSISTANT, "198.51.100.20"));
		}
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(from("POST", ASSISTANT, "198.51.100.20"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNull();
	}

	// CloudFront appends the address it saw, so the last entry is the caller; anything before it is the caller's word.
	@Test
	void onlyTheLastForwardedAddressCountsSoAForgedFirstOneBuysNoNewBudget() throws Exception {
		for (int i = 0; i < 100; i++) {
			call(from("POST", ASSISTANT, "10.0.0." + i + ", 198.51.100.20"));
		}

		assertThat(call(from("POST", ASSISTANT, "203.0.113.99 , 198.51.100.20")).getStatus()).isEqualTo(429);
		assertThat(call(from("POST", ASSISTANT, "198.51.100.20, 203.0.113.99")).getStatus()).isEqualTo(200);
	}

	@Test
	void withoutAForwardedAddressTheConnectionsOwnAddressIsUsed() throws Exception {
		for (int i = 0; i < 100; i++) {
			call(from("POST", ASSISTANT, " "));
		}

		MockHttpServletRequest sameConnection = new MockHttpServletRequest("POST", ASSISTANT);
		MockHttpServletRequest anotherConnection = new MockHttpServletRequest("POST", ASSISTANT);
		anotherConnection.setRemoteAddr("192.0.2.44");

		assertThat(call(sameConnection).getStatus()).isEqualTo(429);
		assertThat(call(anotherConnection).getStatus()).isEqualTo(200);
	}

	@Test
	void otherPathsAreNeitherCountedNorLabelled() throws Exception {
		for (int i = 0; i < 120; i++) {
			MockHttpServletResponse response = call(from("GET", "/api/programs", "198.51.100.20"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("X-Rate-Limit-Remaining")).isNull();
		}
	}

	@Test
	void uploadingAndPreviewingACvSpendTheSameTenPerAccount() throws Exception {
		signIn(new AppUserPrincipal(UUID.randomUUID(), "founder@dongu.co", UserRole.MENTEE,
				UserAccountStatus.APPROVED, "uid-1"));
		for (int i = 0; i < 5; i++) {
			assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
			assertThat(call(from("post", CV_PARSE + "/preview", "198.51.100.20")).getStatus()).isEqualTo(200);
		}

		assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(429);
		assertThat(call(from("POST", CV_PARSE + "/preview", "198.51.100.20")).getStatus()).isEqualTo(429);
		// Reading is not parsing: the same path with GET is outside the budget.
		assertThat(call(from("GET", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
	}

	// During onboarding there is a Firebase login but no account yet; the budget still follows the person.
	@Test
	void aFirebaseLoginWithoutAnAccountHasItsOwnCvBudget() throws Exception {
		signIn(new FirebaseIdentity("uid-new", "yeni@dongu.co", true));
		for (int i = 0; i < 10; i++) {
			assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
		}
		assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(429);

		signIn(new FirebaseIdentity("uid-other", "diger@dongu.co", true));

		assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
	}

	// Sign-up is open, so a script can make a fresh login for every parse; the address still runs out.
	@Test
	void freshLoginsFromOneAddressShareThirtyPreviewsBetweenThem() throws Exception {
		for (int i = 0; i < 30; i++) {
			signIn(new FirebaseIdentity("uid-" + i, "bot" + i + "@example.com", true));
			String path = i % 2 == 0 ? CV_PARSE + "/preview" : CV_PARSE + "/preview/documents/" + UUID.randomUUID();
			assertThat(call(from("POST", path, "198.51.100.20")).getStatus()).isEqualTo(200);
		}
		signIn(new FirebaseIdentity("uid-30", "bot30@example.com", true));

		MockHttpServletResponse refused = call(from("POST", CV_PARSE + "/preview", "198.51.100.20"));

		assertThat(refused.getStatus()).isEqualTo(429);
		assertThat(Long.parseLong(refused.getHeader(RateLimitingFilter.RETRY_AFTER_HEADER))).isBetween(1L, 600L);
		assertThat(call(from("POST", CV_PARSE + "/preview", "198.51.100.21")).getStatus()).isEqualTo(200);
	}

	@Test
	void aPreviewReportsWhicheverOfItsTwoBudgetsHasLessLeft() throws Exception {
		signIn(new FirebaseIdentity("uid-1", "yeni@dongu.co", true));

		assertThat(call(from("POST", CV_PARSE + "/preview", "198.51.100.20")).getHeader("X-Rate-Limit-Remaining"))
				.isEqualTo("9");
	}

	// The address budget is only for the preview, which needs no account; the other parses are per account.
	@Test
	void parsesThatNeedAnAccountDoNotSpendTheAddressBudget() throws Exception {
		for (int i = 0; i < 31; i++) {
			signIn(new AppUserPrincipal(UUID.randomUUID(), "founder@dongu.co", UserRole.MENTEE,
					UserAccountStatus.APPROVED, "uid-" + i));
			assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
		}

		assertThat(call(from("POST", CV_PARSE + "/preview", "198.51.100.20")).getStatus()).isEqualTo(200);
	}

	// The proxy's shared Basic login is one identity for everybody, so it cannot be the key.
	@Test
	void theSharedServiceLoginAndAnonymousCallersAreBudgetedByAddress() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("service", null, List.of()));
		for (int i = 0; i < 10; i++) {
			assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(200);
		}
		assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(429);
		assertThat(call(from("POST", CV_PARSE, "198.51.100.21")).getStatus()).isEqualTo(200);

		SecurityContextHolder.clearContext();

		assertThat(call(from("POST", CV_PARSE, "198.51.100.20")).getStatus()).isEqualTo(429);
	}

	private MockHttpServletResponse call(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static MockHttpServletRequest from(String method, String path, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.addHeader("X-Forwarded-For", forwardedFor);
		return request;
	}

	private static void signIn(Object principal) {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, List.of()));
	}
}
