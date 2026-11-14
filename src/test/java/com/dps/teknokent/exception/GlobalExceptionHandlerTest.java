package com.dps.teknokent.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;

class GlobalExceptionHandlerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Failing())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@RestController
	static class Failing {

		@GetMapping("/duplicate")
		String duplicate() {
			throw new DataIntegrityViolationException("could not execute statement",
					new SQLException("duplicate key value violates unique constraint \"uk_app_user_email\""));
		}

		@GetMapping("/slots-full")
		String slotsFull() {
			throw new ConflictException("Şu anda tüm kayıt yerleri dolu.", "RECORDING_SLOTS_FULL");
		}

		@GetMapping("/provider")
		String provider() {
			throw new MeetingProviderException("Could not send the bot", HttpServerErrorException.create(
					HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY,
					"upstream vexa.internal:8056 refused".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
		}
	}

	@Test
	void aUniqueKeyRaceIsAConflictWithATranslatedTextAndNoSql() throws Exception {
		mockMvc.perform(get("/duplicate").header(HttpHeaders.ACCEPT_LANGUAGE, "tr"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value(
						"Bu kayıt aynı anda yapılan başka bir değişiklikle çakıştı. Sayfayı yenileyip tekrar dene."))
				.andExpect(jsonPath("$.path").value("/duplicate"))
				.andExpect(content().string(not(containsString("uk_app_user_email"))))
				.andExpect(content().string(not(containsString("statement"))));
	}

	@Test
	void theConflictTextFollowsTheCallersLanguage() throws Exception {
		mockMvc.perform(get("/duplicate").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(
						"This record clashed with another change made at the same time. Refresh the page and try again."));
	}

	@Test
	void aConflictCarriesItsCodeWhenItHasOne() throws Exception {
		mockMvc.perform(get("/slots-full"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RECORDING_SLOTS_FULL"))
				.andExpect(jsonPath("$.message").value("Şu anda tüm kayıt yerleri dolu."));
	}

	@Test
	void aProviderFailureIsABadGatewayWithAFixedTextAndCode() throws Exception {
		mockMvc.perform(get("/provider").header(HttpHeaders.ACCEPT_LANGUAGE, "tr"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEETING_PROVIDER_UNAVAILABLE"))
				.andExpect(jsonPath("$.message")
						.value("Toplantı asistanına şu anda ulaşılamıyor. Biraz sonra tekrar dene."))
				.andExpect(content().string(not(containsString("vexa.internal"))));
	}
}
