package com.dps.teknokent.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import com.dps.teknokent.util.TimeFormatUtils;
import com.dps.teknokent.dto.provider.ProviderBotDetails;
import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.requests.GenerateMeetingReportRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantService {

  private final MeetingProviderClient providerClient;
  private final MeetingReportService meetingReportService;

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

  public Optional<Set<String>> runningBotKeys() {
    ProviderBotStatusResponse body = providerClient.getRunningBots();
    if (body == null || body.runningBots() == null) {
      return Optional.empty();
    }
    return Optional.of(body.runningBots().stream()
        .filter(bot -> bot.nativeMeetingId() != null && !bot.nativeMeetingId().isBlank())
        .map(bot -> botKey(bot.platform(), bot.nativeMeetingId()))
        .collect(Collectors.toCollection(LinkedHashSet::new)));
  }

  public static String botKey(String platform, String nativeMeetingId) {
    return normalized(platform) + ":" + normalized(nativeMeetingId);
  }

  public ProviderBotDetails botDetails(String platform, String nativeMeetingId) {
    return providerClient.getBot(platform, nativeMeetingId);
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  public MeetingReportResponse generateMeetingReport(
      String platform,
      String nativeMeetingId,
      GenerateMeetingReportRequest request) {
    String model = request != null ? request.model() : null;
    List<TranscriptSegmentResponse> provided =
        request != null && request.segments() != null ? request.segments() : List.of();
    List<TranscriptSegmentResponse> segments = provided.isEmpty()
        ? getTranscript(platform, nativeMeetingId).segments()
        : provided;
    return meetingReportService.generate(segments, model);
  }

}
