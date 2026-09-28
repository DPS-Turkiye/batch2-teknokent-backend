package com.dps.teknokent.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.security.access.AccessDeniedException;
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
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AssistantReportGeneration;
import com.dps.teknokent.entity.AssistantSession;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.dto.responses.AssistantSessionResponse;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.AssistantReportGenerationRepository;
import com.dps.teknokent.repository.AssistantSessionRepository;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssistantService {

  private final MeetingProviderClient providerClient;
  private final MeetingReportService meetingReportService;
  private final AssistantSessionRepository sessionRepository;
  private final AssistantReportGenerationRepository reportGenerationRepository;
  private final AppUserRepository appUserRepository;
  private final MeetingRepository meetingRepository;

  public AddBotResponse addBot(AddBotRequest request) {
    var parsed = MeetingUrlParser.parse(request.meetingUrl());
    ProviderBotResponse body = providerClient.sendBot(
        parsed.platform(),
        parsed.nativeMeetingId(),
        request.meetingUrl().trim()
    );

    return new AddBotResponse(body.id() != null ? body.id() : "", null,
        parsed.platform(), body.status() != null ? body.status() : "requested",
        body.title() != null ? body.title() : "New meeting bot");
  }

  @Transactional
  public AddBotResponse createSession(AddBotRequest request, UUID actorId) {
    MeetingUrlParser.Parsed parsed = MeetingUrlParser.parse(request.meetingUrl());
    sessionRepository.lockProviderMeeting(parsed.platform(), parsed.nativeMeetingId());
    AppUser actor = appUserRepository.findById(actorId)
        .orElseThrow(() -> new AccessDeniedException("Authenticated account is unavailable"));
    Meeting scheduled = findParticipantMeeting(actor, parsed.nativeMeetingId()).orElse(null);

    var existing = sessionRepository.findByPlatformAndNativeMeetingId(parsed.platform(), parsed.nativeMeetingId());
    if (existing.isPresent()) {
      AssistantSession session = existing.get();
      if (session.getOwner() != null && session.getOwner().getId().equals(actorId)) {
        return sessionResponse(session, "active", "Meeting assistant");
      }
      if (session.getMeeting() != null && isParticipant(actor, session.getMeeting())) {
        return sessionResponse(session, "active", "Meeting assistant");
      }
      if (hasScheduledParticipant(actor, parsed.nativeMeetingId())) {
        return sessionResponse(session, "active", "Meeting assistant");
      }
      throw new AccessDeniedException("This meeting assistant belongs to another account");
    }

    if (scheduled != null) {
      var scheduledSession = sessionRepository.findByMeetingId(scheduled.getId());
      if (scheduledSession.isPresent()) {
        return sessionResponse(scheduledSession.get(), "active", "Meeting assistant");
      }
    }

    AddBotResponse providerResponse = addBot(request);
    AssistantSession session = new AssistantSession();
    session.setPlatform(parsed.platform());
    session.setNativeMeetingId(parsed.nativeMeetingId());
    if (scheduled == null) session.setOwner(actor);
    else session.setMeeting(scheduled);
    sessionRepository.saveAndFlush(session);
    return new AddBotResponse(session.getId().toString(), session.getId().toString(),
        parsed.platform(), providerResponse.status(), providerResponse.title());
  }

  @Transactional(readOnly = true)
  public TranscriptResponse getTranscript(UUID sessionId, UUID actorId) {
    AssistantSession session = requireAccessibleSession(sessionId, actorId);
    return getTranscript(session.getPlatform(), session.getNativeMeetingId());
  }

  @Transactional(readOnly = true)
  public AssistantSessionResponse getScheduledSession(UUID meetingId, UUID actorId) {
    AppUser actor = appUserRepository.findById(actorId)
        .orElseThrow(() -> new AccessDeniedException("Authenticated account is unavailable"));
    Meeting meeting = meetingRepository.findById(meetingId)
        .filter(candidate -> isParticipant(actor, candidate))
        .orElseThrow(() -> new AccessDeniedException("Scheduled meeting is unavailable"));
    String code = MeetingUrlParser.tryParse(meeting.getMeetLink())
        .map(MeetingUrlParser.Parsed::nativeMeetingId)
        .orElseThrow(() -> new AccessDeniedException("Meeting assistant session is unavailable"));
    AssistantSession session = sessionRepository.findByPlatformAndNativeMeetingId("google_meet", code)
        .orElseThrow(() -> new AccessDeniedException("Meeting assistant session is unavailable"));
    return new AssistantSessionResponse(session.getId().toString());
  }

  @Transactional
  public UUID stopSession(UUID sessionId, UUID actorId) {
    AssistantSession session = requireAccessibleSession(sessionId, actorId);
    stopBot(session.getPlatform(), session.getNativeMeetingId());
    return session.getMeeting() == null ? null : session.getMeeting().getId();
  }

  @Transactional(isolation = Isolation.SERIALIZABLE)
  public MeetingReportResponse generateMeetingReport(
      UUID sessionId, UUID actorId, GenerateMeetingReportRequest request) {
    AssistantSession session = requireAccessibleSession(sessionId, actorId);
    AppUser actor = appUserRepository.findByIdForUpdate(actorId)
        .orElseThrow(() -> new AccessDeniedException("Authenticated account is unavailable"));
    Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    if (reportGenerationRepository.countByActorIdAndCreatedAtAfter(actorId, dayStart) >= 20) {
      throw new ReportLimitExceededException();
    }
    reportGenerationRepository.saveAndFlush(new AssistantReportGeneration(actor));
    String model = request != null ? request.model() : null;
    List<TranscriptSegmentResponse> provided = request != null && request.segments() != null
        ? request.segments() : List.of();
    List<TranscriptSegmentResponse> segments = provided.isEmpty()
        ? getTranscript(session.getPlatform(), session.getNativeMeetingId()).segments() : provided;
    return meetingReportService.generate(segments, model);
  }

  @Transactional
  public void registerScheduledBot(String meetingUrl, List<Meeting> meetings) {
    MeetingUrlParser.Parsed parsed = MeetingUrlParser.parse(meetingUrl);
    sessionRepository.lockProviderMeeting(parsed.platform(), parsed.nativeMeetingId());
    if (sessionRepository.findByPlatformAndNativeMeetingId(parsed.platform(), parsed.nativeMeetingId()).isPresent()) return;
    Meeting meeting = meetings.stream().filter(candidate -> isSameMeeting(candidate, parsed.nativeMeetingId()))
        .findFirst().orElse(null);
    if (meeting == null) return;
    AssistantSession session = new AssistantSession();
    session.setPlatform(parsed.platform());
    session.setNativeMeetingId(parsed.nativeMeetingId());
    session.setMeeting(meeting);
    sessionRepository.save(session);
  }

  @Transactional(readOnly = true)
  public boolean hasProviderSession(String meetingUrl) {
    MeetingUrlParser.Parsed parsed = MeetingUrlParser.parse(meetingUrl);
    return sessionRepository.findByPlatformAndNativeMeetingId(parsed.platform(), parsed.nativeMeetingId()).isPresent();
  }

  @Transactional
  public void reserveProviderMeeting(String meetingUrl) {
    MeetingUrlParser.Parsed parsed = MeetingUrlParser.parse(meetingUrl);
    sessionRepository.lockProviderMeeting(parsed.platform(), parsed.nativeMeetingId());
  }

  private AssistantSession requireAccessibleSession(UUID sessionId, UUID actorId) {
    AssistantSession session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new AccessDeniedException("Assistant session is unavailable"));
    if (session.getOwner() != null && session.getOwner().getId().equals(actorId)) return session;
    if (session.getMeeting() != null) {
      AppUser actor = appUserRepository.findById(actorId)
          .orElseThrow(() -> new AccessDeniedException("Authenticated account is unavailable"));
      if (isParticipant(actor, session.getMeeting())) return session;
      if (hasScheduledParticipant(actor, session.getNativeMeetingId())) return session;
    }
    if (hasScheduledParticipant(
        appUserRepository.findById(actorId)
            .orElseThrow(() -> new AccessDeniedException("Authenticated account is unavailable")),
        session.getNativeMeetingId())) return session;
    throw new AccessDeniedException("This assistant session belongs to another account");
  }

  private boolean hasScheduledParticipant(AppUser actor, String meetingCode) {
    return meetingRepository.findByMeetLinkContainingIgnoreCase(meetingCode).stream()
        .anyMatch(meeting -> isSameMeeting(meeting, meetingCode) && isParticipant(actor, meeting));
  }

  private java.util.Optional<Meeting> findParticipantMeeting(AppUser actor, String meetingCode) {
    return meetingRepository.findByMeetLinkContainingIgnoreCase(meetingCode).stream()
        .filter(meeting -> isSameMeeting(meeting, meetingCode) && isParticipant(actor, meeting))
        .findFirst();
  }

  private static boolean isSameMeeting(Meeting meeting, String meetingCode) {
    return MeetingUrlParser.tryParse(meeting.getMeetLink())
        .map(parsed -> parsed.nativeMeetingId().equals(meetingCode)).orElse(false);
  }

  private static boolean isParticipant(AppUser actor, Meeting meeting) {
    return (meeting.getMentor() != null && meeting.getMentor().getId().equals(actor.getId()))
        || (actor.getOrganization() != null && meeting.getOrganization() != null
            && actor.getOrganization().getId().equals(meeting.getOrganization().getId()));
  }

  private static AddBotResponse sessionResponse(AssistantSession session, String status, String title) {
    return new AddBotResponse(session.getId().toString(), session.getId().toString(),
        session.getPlatform(), status, title);
  }

  public static class ReportLimitExceededException extends RuntimeException {
    public ReportLimitExceededException() { super("Daily assistant report limit reached"); }
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
