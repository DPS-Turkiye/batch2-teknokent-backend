package com.dps.teknokent.service;

import java.util.List;

import org.springframework.stereotype.Service;
import com.dps.teknokent.util.TimeFormatUtils;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantService {

  private final MeetingProviderClient providerClient;

  public AddBotResponse addBot(AddBotRequest request) {
    var parsed = MeetingUrlParser.parse(request.meetingUrl());
    ProviderBotResponse body = providerClient.sendBot(
        parsed.platform(),
        parsed.nativeMeetingId(),
        request.meetingUrl().trim()
    );

    return new AddBotResponse(
        body.id() != null ? body.id() : "",
        parsed.platform(),
        parsed.nativeMeetingId(),
        body.status() != null ? body.status() : "requested",
        body.title() != null ? body.title() : "New meeting bot"
    );
  }

  public TranscriptResponse getTranscript(String platform, String nativeMeetingId) {
    ProviderTranscriptResponse body =
        providerClient.getTranscript(platform, nativeMeetingId);

    List<ProviderTranscriptSegment> raw =
        body != null && body.segments() != null ? body.segments() : List.of();

    List<TranscriptSegmentResponse> segments = raw.stream()
        .map(seg -> new TranscriptSegmentResponse(
            seg.segmentId() != null ? seg.segmentId() : "",
            seg.speaker() != null ? seg.speaker() : "Speaker",
            seg.text() != null ? seg.text() : "",
            TimeFormatUtils.formatTime(seg.start())
        ))
        .toList();

    return new TranscriptResponse(segments);
  }

  public void stopBot(String platform, String nativeMeetingId) {
    providerClient.stopBot(platform, nativeMeetingId);
  }

}
