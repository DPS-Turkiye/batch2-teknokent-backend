package com.dps.teknokent.service;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderRunningBot;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.requests.GenerateMeetingReportRequest;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.AssistantReportGenerationRepository;
import com.dps.teknokent.repository.AssistantSessionRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AssistantSession;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

  @Mock
  private MeetingProviderClient providerClient;

  @Mock
  private MeetingReportService meetingReportService;

  @Mock
  private AssistantSessionRepository sessionRepository;

  @Mock
  private AssistantReportGenerationRepository reportGenerationRepository;

  @Mock
  private AppUserRepository appUserRepository;

  @Mock
  private MeetingRepository meetingRepository;

  @InjectMocks
  private AssistantService assistantService;

  @Test
  void addBotParsesUrlAndMapsProviderResponse() {
    String url = "https://meet.google.com/abc-defg-hij";
    when(providerClient.sendBot("google_meet", "abc-defg-hij", url))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    var response = assistantService.addBot(new AddBotRequest(url));

    assertThat(response.id()).isEqualTo("bot-1");
    assertThat(response.platform()).isEqualTo("google_meet");
    assertThat(response.assistantSessionId()).isNull();
    assertThat(response.status()).isEqualTo("joining");
    assertThat(response.title()).isEqualTo("Mentor Bot");
    verify(providerClient).sendBot("google_meet", "abc-defg-hij", url);
  }

  @Test
  void runningBotKeysListsTheBotsStillInAMeeting() {
    when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(List.of(
        new ProviderRunningBot("google_meet", "abc-defg-hij", "running"),
        new ProviderRunningBot("google_meet", null, "starting")
    )));

    assertThat(assistantService.runningBotKeys()).contains(Set.of("google_meet:abc-defg-hij"));
  }

  @Test
  void runningBotKeysStaysUnknownWhenTheProviderAnswerHasNoBotList() {
    when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(null));

    assertThat(assistantService.runningBotKeys()).isEmpty();
  }

  @Test
  void addBotRejectsBadUrlWithoutCallingProvider() {
    assertThatThrownBy(() -> assistantService.addBot(new AddBotRequest("https://zoom.us/j/1")))
        .isInstanceOf(IllegalArgumentException.class);

    verify(providerClient, never()).sendBot(any(), any(), any());
  }

  @Test
  void getTranscriptMapsSegmentsAndFormatsTime() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).hasSize(1);
    assertThat(response.segments().get(0).id()).isEqualTo("s1");
    assertThat(response.segments().get(0).speaker()).isEqualTo("Ayşe");
    assertThat(response.segments().get(0).text()).isEqualTo("Merhaba");
    assertThat(response.segments().get(0).at()).isEqualTo("01:05");
  }

  @Test
  void getTranscriptReturnsEmptyListWhenSegmentsMissing() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of()));

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).isEmpty();
  }

  @Test
  void generateMeetingReportUsesProvidedSegmentsWithoutFetchingTheProviderTranscript() {
    when(meetingReportService.generate(any(), eq(null)))
        .thenReturn(report("Edited"));

    var edited = new TranscriptSegmentResponse("s1", "Ayse Kaya", "Duzeltilmis metin", "00:10");
    assistantService.generateMeetingReport(
        "google_meet",
        "abc-defg-hij",
        new GenerateMeetingReportRequest(null, List.of(edited)));

    verify(providerClient, never()).getTranscript(any(), any());
    verify(meetingReportService).generate(List.of(edited), null);
  }

  @Test
  void generateMeetingReportFallsBackToProviderTranscriptWhenSegmentsAreMissing() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(any(), eq(null)))
        .thenReturn(report("Original"));

    assistantService.generateMeetingReport(
        "google_meet",
        "abc-defg-hij",
        new GenerateMeetingReportRequest(null, List.of()));

    verify(providerClient).getTranscript("google_meet", "abc-defg-hij");
  }

  @Test
  void reportGenerationStopsAtTwentyAndLocksActorBeforeCounting() {
    UUID actorId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    AppUser actor = new AppUser();
    actor.setId(actorId);
    AssistantSession session = new AssistantSession();
    session.setId(sessionId);
    session.setOwner(actor);
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(appUserRepository.findByIdForUpdate(actorId)).thenReturn(Optional.of(actor));
    when(reportGenerationRepository.countByActorIdAndCreatedAtAfter(eq(actorId), any()))
        .thenReturn(20L);

    assertThatThrownBy(() -> assistantService.generateMeetingReport(sessionId, actorId, null))
        .isInstanceOf(AssistantService.ReportLimitExceededException.class);

    verify(appUserRepository).findByIdForUpdate(actorId);
    verify(meetingReportService, never()).generate(any(), any());
  }

  private static MeetingReportResponse report(String title) {
    return new MeetingReportResponse(
        title,
        null,
        null,
        "Ozet",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "gpt-4o-mini");
  }
}
