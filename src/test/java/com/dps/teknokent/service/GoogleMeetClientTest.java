package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class GoogleMeetClientTest {

	private MockRestServiceServer server;
	private GoogleMeetClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://meet.googleapis.com");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new GoogleMeetClient(builder.build(), JsonMapper.builder().build(), () -> "token-1", "OPEN");
	}

	@Test
	void makesAMeetingAnyoneWithTheLinkCanJoinAndAnswersItsLink() {
		server.expect(requestTo("https://meet.googleapis.com/v2/spaces"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer token-1"))
				.andExpect(content().json("{\"config\":{\"accessType\":\"OPEN\"}}"))
				.andRespond(withSuccess("""
						{ "name": "spaces/x", "meetingUri": "https://meet.google.com/abc-mnop-xyz",
						  "meetingCode": "abc-mnop-xyz" }
						""", MediaType.APPLICATION_JSON));

		assertThat(client.createMeeting()).isEqualTo("https://meet.google.com/abc-mnop-xyz");
		server.verify();
	}

	@Test
	void refusesAnAnswerWithoutAMeetLink() {
		server.expect(requestTo("https://meet.googleapis.com/v2/spaces"))
				.andRespond(withSuccess("{\"meetingUri\":\"https://example.test/x\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(client::createMeeting).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void passesOnGooglesRefusal() {
		server.expect(requestTo("https://meet.googleapis.com/v2/spaces"))
				.andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThatThrownBy(client::createMeeting).isInstanceOf(HttpClientErrorException.Forbidden.class);
	}
}
