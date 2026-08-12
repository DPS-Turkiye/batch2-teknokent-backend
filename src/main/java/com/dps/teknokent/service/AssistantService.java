package com.dps.teknokent.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantService {

  private final MeetingProviderClient providerClient;

  //bot ekleme içinn
  public AddBotResponse addBot(AddBotRequest request) {
    //öncce meetingurl parse eder
    var parsed = MeetingUrlParser.parse(request.meetingUrl());
    Map<String, Object> body = providerClient.sendBot(
        parsed.platform(),
        parsed.nativeMeetingId(),
        request.meetingUrl().trim()
    );
//vexa cevabını adddbotresponse a çevirir
    return new AddBotResponse(
        String.valueOf(body.getOrDefault("id", "")),
        parsed.platform(),
        parsed.nativeMeetingId(),
        String.valueOf(body.getOrDefault("status", "requested")),
        String.valueOf(body.getOrDefault("title", "Yeni toplantı botu"))
    );
  }
  //transkript içinn
  public TranscriptResponse getTranscript(String platform, String nativeMeetingId) {
    Map<String, Object> body = providerClient.getTranscript(platform, nativeMeetingId);
  //segments listesini okur
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> raw =
        (List<Map<String, Object>>) body.getOrDefault("segments", List.of());
  //her segmenti mapler
    List<TranscriptSegmentResponse> segments = raw.stream()
        .map(seg -> new TranscriptSegmentResponse(
            String.valueOf(seg.getOrDefault("segment_id", "")),
            String.valueOf(seg.getOrDefault("speaker", "Konuşmacı")),
            String.valueOf(seg.getOrDefault("text", "")),
            formatTime(seg.get("start"))
        ))
        .toList();
  
    return new TranscriptResponse(segments);
  }
  
  private static String formatTime(Object start) {
    if (start == null) return "";
    double seconds = Double.parseDouble(String.valueOf(start));
    int m = (int) (seconds / 60);
    int s = (int) (seconds % 60);
    return String.format("%02d:%02d", m, s);
  }
}