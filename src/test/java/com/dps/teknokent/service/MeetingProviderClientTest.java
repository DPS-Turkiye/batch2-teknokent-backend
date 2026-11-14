package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderBotSummary;
import com.dps.teknokent.dto.provider.ProviderRunningBot;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;

import tools.jackson.databind.json.JsonMapper;

class MeetingProviderClientTest {

	private MockRestServiceServer server;
	private MeetingProviderClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new MeetingProviderClient(builder.build(), JsonMapper.builder().build());
	}

	@Test
	void sendBotPostsExpectedPayloadAndMapsResponse() {
		server.expect(requestTo("http://localhost/bots"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(content().json("""
						{
						  "platform": "google_meet",
						  "native_meeting_id": "abc-defg-hij",
						  "meeting_url": "https://meet.google.com/abc-defg-hij",
						  "bot_name": "Mentor Bot",
						  "automatic_leave": {
						    "max_wait_for_admission": 900000,
						    "max_time_left_alone": 90000
						  }
						}
						"""))
				.andRespond(withSuccess(
						"""
								{"id":"bot-1","status":"joining","title":"Mentor Bot"}
								""",
						MediaType.APPLICATION_JSON));

		ProviderBotResponse response = client.sendBot(
				"google_meet",
				"abc-defg-hij",
				"https://meet.google.com/abc-defg-hij");

		assertThat(response.id()).isEqualTo("bot-1");
		assertThat(response.status()).isEqualTo("joining");
		assertThat(response.title()).isEqualTo("Mentor Bot");
		server.verify();
	}

	@Test
	void findLatestBotReturnsTheNewestRowForTheMeeting() {
		server.expect(requestTo("http://localhost/bots?platform=google_meet&limit=25"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"""
								{"meetings":[
								  {"id":52,"platform":"google_meet","native_meeting_id":"zzz-zzzz-zzz","status":"active"},
								  {"id":51,"platform":"google_meet","native_meeting_id":"abc-defg-hij","status":"failed",
								   "completion_reason":"join_failure","failure_stage":"awaiting_admission",
								   "created_at":"2026-11-11T20:03:06.512Z"},
								  {"id":49,"platform":"google_meet","native_meeting_id":"abc-defg-hij","status":"failed",
								   "completion_reason":"stopped"}
								],"has_more":false}
								""",
						MediaType.APPLICATION_JSON));

		ProviderBotSummary latest = client.findLatestBot("google_meet", "abc-defg-hij");

		assertThat(latest.id()).isEqualTo(51L);
		assertThat(latest.status()).isEqualTo("failed");
		assertThat(latest.completionReason()).isEqualTo("join_failure");
		assertThat(latest.createdAt()).isEqualTo(java.time.Instant.parse("2026-11-11T20:03:06.512Z"));
		server.verify();
	}

	@Test
	void findLatestBotIsNullWhenTheMeetingHasNoBot() {
		server.expect(requestTo("http://localhost/bots?platform=google_meet&limit=25"))
				.andRespond(withSuccess("{\"meetings\":[]}", MediaType.APPLICATION_JSON));

		assertThat(client.findLatestBot("google_meet", "abc-defg-hij")).isNull();
	}

	@Test
	void getTranscriptCallsExpectedUriAndMapsSegments() {
		server.expect(requestTo("http://localhost/transcripts/google_meet/abc-defg-hij"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"""
								{
								  "segments": [
								    {
								      "segment_id": "s1",
								      "speaker": "Ayse",
								      "text": "Merhaba",
								      "start": 65.0
								    }
								  ]
								}
								""",
						MediaType.APPLICATION_JSON));

		ProviderTranscriptResponse response = client.getTranscript("google_meet", "abc-defg-hij");

		assertThat(response.segments()).hasSize(1);
		assertThat(response.segments().get(0).segmentId()).isEqualTo("s1");
		assertThat(response.segments().get(0).speaker()).isEqualTo("Ayse");
		assertThat(response.segments().get(0).text()).isEqualTo("Merhaba");
		assertThat(response.segments().get(0).start()).isEqualTo(65.0);
		server.verify();
	}

	@Test
	void findLatestBotMatchesTheMeetingCodeWhateverItsCase() {
		server.expect(requestTo("http://localhost/bots?platform=google_meet&limit=25"))
				.andRespond(withSuccess(
						"""
								{"meetings":[
								  {"id":60,"platform":"google_meet","native_meeting_id":null,"status":"failed"},
								  {"id":58,"platform":"google_meet","native_meeting_id":"ABC-DEFG-HIJ","status":"active"}
								]}
								""",
						MediaType.APPLICATION_JSON));

		ProviderBotSummary latest = client.findLatestBot("google_meet", "abc-defg-hij");

		assertThat(latest.id()).isEqualTo(58L);
		assertThat(latest.status()).isEqualTo("active");
	}

	@Test
	void findLatestBotIsNullWhenTheProviderAnswersWithoutABotList() {
		server.expect(requestTo("http://localhost/bots?platform=google_meet&limit=25"))
				.andRespond(withSuccess("{\"has_more\":false}", MediaType.APPLICATION_JSON));

		assertThat(client.findLatestBot("google_meet", "abc-defg-hij")).isNull();
	}

	@Test
	void getRunningBotsListsTheBotsTheProviderIsRunning() {
		server.expect(requestTo("http://localhost/bots/status"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"""
								{"running_bots":[
								  {"platform":"google_meet","native_meeting_id":"abc-defg-hij","status":"running"},
								  {"platform":"teams","native_meeting_id":"9387612345","status":"starting"}
								]}
								""",
						MediaType.APPLICATION_JSON));

		ProviderBotStatusResponse response = client.getRunningBots();

		assertThat(response.runningBots()).containsExactly(
				new ProviderRunningBot("google_meet", "abc-defg-hij", "running"),
				new ProviderRunningBot("teams", "9387612345", "starting"));
		server.verify();
	}

	@Test
	void getBotReadsTheBotOfOneMeeting() {
		server.expect(requestTo("http://localhost/bots/google_meet/abc-defg-hij"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"""
								{"status":"active","platform":"google_meet","native_meeting_id":"abc-defg-hij",
								 "participants":["Ayşe","Mentor Bot"],"attendees":[],"bot_container_id":"c-1"}
								""",
						MediaType.APPLICATION_JSON));

		ProviderBotDetails bot = client.getBot("google_meet", "abc-defg-hij");

		assertThat(bot.status()).isEqualTo("active");
		assertThat(bot.nativeMeetingId()).isEqualTo("abc-defg-hij");
		assertThat(bot.participants()).hasSize(2);
		assertThat(bot.attendees()).isEmpty();
		server.verify();
	}

	@Test
	void getBotIsNullWhenTheProviderHasNoBotForTheMeeting() {
		server.expect(requestTo("http://localhost/bots/google_meet/abc-defg-hij"))
				.andRespond(withResourceNotFound());

		assertThat(client.getBot("google_meet", "abc-defg-hij")).isNull();
	}

	// Only "no such bot" means there is none; an outage must not read as a meeting without a bot.
	@Test
	void getBotPassesOtherProviderErrorsOn() {
		server.expect(requestTo("http://localhost/bots/google_meet/abc-defg-hij"))
				.andRespond(withServerError());

		assertThatThrownBy(() -> client.getBot("google_meet", "abc-defg-hij"))
				.isInstanceOf(HttpServerErrorException.class)
				.extracting(error -> ((HttpServerErrorException) error).getStatusCode())
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void stopBotAsksTheProviderToRemoveTheMeetingsBot() {
		server.expect(requestTo("http://localhost/bots/google_meet/abc-defg-hij"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{\"message\":\"Stop request accepted\"}", MediaType.APPLICATION_JSON));

		client.stopBot("google_meet", "abc-defg-hij");

		server.verify();
	}

	// AssistantService tells "already gone" (404) from a real failure by this exception.
	@Test
	void stopBotReportsABotTheProviderDoesNotKnow() {
		server.expect(requestTo("http://localhost/bots/google_meet/abc-defg-hij"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withResourceNotFound());

		assertThatThrownBy(() -> client.stopBot("google_meet", "abc-defg-hij"))
				.isInstanceOf(HttpClientErrorException.NotFound.class);
	}
}
