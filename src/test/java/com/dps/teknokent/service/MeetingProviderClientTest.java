package com.dps.teknokent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
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

    ProviderTranscriptResponse response =
        client.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).hasSize(1);
    assertThat(response.segments().get(0).segmentId()).isEqualTo("s1");
    assertThat(response.segments().get(0).speaker()).isEqualTo("Ayse");
    assertThat(response.segments().get(0).text()).isEqualTo("Merhaba");
    assertThat(response.segments().get(0).start()).isEqualTo(65.0);
    server.verify();
  }
}