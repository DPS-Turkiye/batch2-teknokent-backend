package com.dps.teknokent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
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
    payload.put("bot_name", ProviderMeetingPresence.BOT_NAME);
    Map<String, Object> automaticLeave = new LinkedHashMap<>();
    automaticLeave.put("waiting_room_timeout", 300);
    automaticLeave.put("noone_joined_timeout", 300);
    automaticLeave.put("everyone_left_timeout", 90);
    payload.put("automatic_leave", automaticLeave);

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

  public ProviderBotStatusResponse getRunningBots() {
    return client.get()
        .uri("/bots/status")
        .retrieve()
        .body(ProviderBotStatusResponse.class);
  }

  public ProviderBotDetails getBot(String platform, String nativeMeetingId) {
    try {
      return client.get()
          .uri("/bots/{platform}/{nativeId}", platform, nativeMeetingId)
          .retrieve()
          .body(ProviderBotDetails.class);
    } catch (org.springframework.web.client.RestClientResponseException missing) {
      if (missing.getStatusCode().value() == 404) {
        return null;
      }
      throw missing;
    }
  }

  public void stopBot(String platform, String nativeMeetingId) {
    client.delete()
        .uri("/bots/{platform}/{nativeId}", platform, nativeMeetingId)
        .retrieve()
        .toBodilessEntity();
  }
}
