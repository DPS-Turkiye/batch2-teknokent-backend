package com.dps.teknokent.service;

import java.time.Instant;
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
import com.dps.teknokent.exception.ConflictException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantService {

  private final MeetingProviderClient providerClient;
  private final MeetingReportService meetingReportService;
  private final TranscriptBotCapacityService capacityService;

  /**
   * A bot asked for from the assistant, outside a booked meeting. It counts the bots running right now plus the
   * booked meetings whose bots join within the hour, so booked recordings keep their slots; when the running count
   * is unknown it refuses.
   */
  public AddBotResponse addAdHocBot(AddBotRequest request) {
    var parsed = MeetingUrlParser.parse(request.meetingUrl());
    Set<String> running = runningBotKeys()
        .orElseThrow(() -> new IllegalStateException("Running bots are unknown"));
    // ponytail: no lock, so two requests at the same moment can both pass, and a meeting booked later does not see
    // ad-hoc bots; the dispatcher's retry on Vexa's 429 covers that until a shared ledger is worth it.
    if (!running.contains(botKey(parsed.platform(), parsed.nativeMeetingId()))
        && running.size() + capacityService.reservedWithinHour(Instant.now()) >= capacityService.capacity()) {
      throw new ConflictException("All recording slots are in use.");
    }
    return addBot(request);
  }

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

    double origin = transcriptOrigin(raw);
    List<TranscriptSegmentResponse> segments = raw.stream()
        .map(seg -> new TranscriptSegmentResponse(
            seg.segmentId() != null ? seg.segmentId() : "",
            seg.speaker() != null ? seg.speaker() : "Speaker",
            seg.text() != null ? seg.text() : "",
            TimeFormatUtils.formatTime(seg.start() == null ? null : seg.start() - origin)
        ))
        .toList();

    return new TranscriptResponse(segments);
  }

  // Vexa 0.12 sends each start as Unix seconds; older versions sent seconds since the meeting began.
  private static final double EPOCH_SECONDS_FLOOR = 1_000_000_000d;

  /** Absolute starts count from the first segment, so the transcript reads 00:00, 00:12, ... */
  private static double transcriptOrigin(List<ProviderTranscriptSegment> segments) {
    return segments.stream()
        .map(ProviderTranscriptSegment::start)
        .filter(start -> start != null && start >= EPOCH_SECONDS_FLOOR)
        .min(Double::compare)
        .orElse(0d);
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
