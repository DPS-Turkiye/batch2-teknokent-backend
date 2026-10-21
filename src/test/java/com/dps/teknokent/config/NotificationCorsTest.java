package com.dps.teknokent.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The browser calls the notification endpoints directly, so every method NotificationController maps has to
 * pass the preflight. Dev and local allow all methods on /**, which hides a gap here; the test profile does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationCorsTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@CsvSource({
			"GET, /api/notifications",
			"PATCH, /api/notifications/read-all",
			"DELETE, /api/notifications/42",
			"PUT, /api/notifications/preferences",
			"POST, /api/notifications/push-tokens",
			"DELETE, /api/notifications/push-tokens"
	})
	void preflightAllowsEveryNotificationMethod(String method, String path) throws Exception {
		mockMvc.perform(options(path)
						.header("Origin", "http://localhost:3000")
						.header("Access-Control-Request-Method", method)
						.header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
				.andExpect(header().string("Access-Control-Allow-Methods", containsString(method)));
	}
}
