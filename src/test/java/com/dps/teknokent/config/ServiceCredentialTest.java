package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The frontend proxy sends the shared Basic credential on every call, so checking it must stay cheap:
 * the stored credential may never be upgraded to a bcrypt hash, which would cost a bcrypt verify per request.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceCredentialTest {

	private static final String UNMAPPED_PATH = "/api/service-credential-probe";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserDetailsService userDetailsService;

	@Test
	void acceptsTheSharedCredentialWithoutUpgradingItToBcrypt() throws Exception {
		// Past the security chain an unmapped path is a 404; a rejected credential stops at 401.
		mockMvc.perform(get(UNMAPPED_PATH).with(httpBasic("test", "test"))).andExpect(status().isNotFound());
		mockMvc.perform(get(UNMAPPED_PATH).with(httpBasic("test", "test"))).andExpect(status().isNotFound());

		assertThat(userDetailsService.loadUserByUsername("test").getPassword()).isEqualTo("test");
	}

	@Test
	void rejectsAWrongPassword() throws Exception {
		mockMvc.perform(get(UNMAPPED_PATH).with(httpBasic("test", "wrong"))).andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsAPasswordThatOnlySharesAPrefix() throws Exception {
		mockMvc.perform(get(UNMAPPED_PATH).with(httpBasic("test", "tes"))).andExpect(status().isUnauthorized());
	}
}
