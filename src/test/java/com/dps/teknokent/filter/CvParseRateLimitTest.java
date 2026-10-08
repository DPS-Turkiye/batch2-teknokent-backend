package com.dps.teknokent.filter;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.ProfileAnalysisService;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rate-limiting.enabled=true")
class CvParseRateLimitTest {

	private static final String OFFICE_IP = "198.51.100.20";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProfileAnalysisService profileAnalysisService;

	@MockitoBean
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void stubParse() {
		when(profileAnalysisService.parseStoredCvPreview(any(), any(), anyString(), anyBoolean(), anyString()))
				.thenReturn(Map.of());
	}

	@Test
	void eleventhCvParseInTheWindowIsRefusedForThatUserOnly() throws Exception {
		Authentication founder = signedIn();
		spendCvBudget(founder);

		mockMvc.perform(preview().with(authentication(founder)).header("Origin", "http://localhost:3000"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("X-Rate-Limit-Retry-After-Seconds"))
				// The upload runs in the browser, which can only read the wait time if CORS exposes it.
				.andExpect(header().string("Access-Control-Expose-Headers",
						containsString("X-Rate-Limit-Retry-After-Seconds")));

		// Same office address, different account: its own budget.
		mockMvc.perform(preview().with(authentication(signedIn()))).andExpect(status().isOk());
	}

	@Test
	void readingProfilesDoesNotCountAgainstTheCvBudget() throws Exception {
		Authentication founder = signedIn();
		spendCvBudget(founder);

		mockMvc.perform(get("/api/profile-analyses/latest/confirmed").with(authentication(founder))
						.header("X-Forwarded-For", OFFICE_IP))
				.andExpect(header().doesNotExist("X-Rate-Limit-Retry-After-Seconds"));
	}

	private void spendCvBudget(Authentication caller) throws Exception {
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(preview().with(authentication(caller))).andExpect(status().isOk());
		}
	}

	private static MockHttpServletRequestBuilder preview() {
		return post("/api/profile-analyses/preview/documents/{id}", UUID.randomUUID())
				.header("X-Forwarded-For", OFFICE_IP);
	}

	private static Authentication signedIn() {
		AppUserPrincipal principal = new AppUserPrincipal(
				UUID.randomUUID(), "founder@example.com", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid");
		return UsernamePasswordAuthenticationToken.authenticated(
				principal, "firebase", List.of(new SimpleGrantedAuthority("ROLE_MENTEE")));
	}
}
