package com.dps.teknokent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;

import tools.jackson.databind.json.JsonMapper;

@Component
public class MeetingProviderClient {

  private final RestClient client;
  private final JsonMapper jsonMapper;

  public MeetingProviderClient(RestClient meetingProviderRestClient, JsonMapper jsonMapper) {
    this.client = meetingProviderRestClient;
    this.jsonMapper = jsonMapper;
  }

  public ProviderBotResponse sendBot(String platform, String nativeId, String meetingUrl) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("platform", platform);
    payload.put("native_meeting_id", nativeId);
    payload.put("meeting_url", meetingUrl);
    payload.put("bot_name", "Mentor Bot");

    byte[] json = jsonMapper.writeValueAsBytes(payload);

    return client.post()
        .uri("/bots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .retrieve()
        .body(ProviderBotResponse.class);
  }

  public ProviderTranscriptResponse getTranscript(String platform, String nativeMeetingId) {
    return client.get()
        .uri("/transcripts/{platform}/{nativeId}", platform, nativeMeetingId)
        .retrieve()
        .body(ProviderTranscriptResponse.class);
  }

  public void stopBot(String platform, String nativeMeetingId) {
    client.delete()
        .uri("/bots/{platform}/{nativeId}", platform, nativeMeetingId)
        .retrieve()
        .toBodilessEntity();
  }
}
