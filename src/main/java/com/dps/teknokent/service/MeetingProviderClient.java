package com.dps.teknokent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

@Component
public class MeetingProviderClient {

  private final RestClient client;
  private final JsonMapper jsonMapper;

  public MeetingProviderClient(RestClient meetingProviderRestClient, JsonMapper jsonMapper) {
    this.client = meetingProviderRestClient;
    this.jsonMapper = jsonMapper;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> sendBot(String platform, String nativeId, String meetingUrl) {
    //JSOn body oluşturur
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("platform", platform);
    payload.put("native_meeting_id", nativeId);
    payload.put("meeting_url", meetingUrl);
    payload.put("bot_name", "Mentor Bot");

    byte[] json = jsonMapper.writeValueAsBytes(payload);//byte array e çevirir,bozuk json olmasın diye

    //rest client ile post request gönderir,client ı kuran ise: MeetingProviderConfig.java,server a gönderdi
    return client.post()
        .uri("/bots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .retrieve()
        .body(Map.class);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getTranscript(String platform, String nativeMeetingId) {
      return client.get()
         .uri("/transcripts/{platform}/{nativeId}", platform, nativeMeetingId)
         .retrieve()
         .body(Map.class);
}
}
