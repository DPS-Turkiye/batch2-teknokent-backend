package com.dps.teknokent.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderBotStatusResponse;
import com.dps.teknokent.dto.provider.ProviderRunningBot;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

  @Mock
  private MeetingProviderClient providerClient;

  @Mock
  private MeetingReportService meetingReportService;

  @Mock
  private TranscriptBotCapacityService capacityService;

  @Mock
  private AssistantMeetingAccessService accessService;

  @Spy
  private AiFeatures aiFeatures = TestAiFeatures.allOn();

  @InjectMocks
  private AssistantService assistantService;

  private static final String URL = "https://meet.google.com/abc-defg-hij";
  private static final UUID CALLER = UUID.randomUUID();

  private void allowed() {
    when(accessService.requireAccess(CALLER, "google_meet", "abc-defg-hij"))
        .thenReturn(new MeetingUrlParser.Parsed("google_meet", "abc-defg-hij"));
  }

  private void running(String... nativeIds) {
    when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(
        java.util.Arrays.stream(nativeIds).map(id -> new ProviderRunningBot("google_meet", id, "running")).toList()));
  }

  @Test
  void addAdHocBotSendsTheBotWhileASlotIsFree() {
    allowed();
    running("aaa-bbbb-ccc");
    when(capacityService.capacity()).thenReturn(2);
    when(providerClient.sendBot("google_meet", "abc-defg-hij", URL))
        .thenReturn(new ProviderBotResponse("bot-1", "requested", "New meeting bot"));

    assertThat(assistantService.addAdHocBot(CALLER, new AddBotRequest(URL)).id()).isEqualTo("bot-1");
  }

  @Test
  void addAdHocBotRefusesWhenEverySlotIsTaken() {
    allowed();
    running("aaa-bbbb-ccc", "ddd-eeee-fff");
    when(capacityService.capacity()).thenReturn(2);

    assertThatThrownBy(() -> assistantService.addAdHocBot(CALLER, new AddBotRequest(URL)))
        .isInstanceOf(ConflictException.class);
    verify(providerClient, never()).sendBot(any(), any(), any());
  }

  @Test
  void addAdHocBotLeavesTheSlotsOfBookedRecordingsDueWithinTheHour() {
    allowed();
    running("aaa-bbbb-ccc");
    when(capacityService.capacity()).thenReturn(3);
    when(capacityService.reservedWithinHour(any())).thenReturn(2);

    assertThatThrownBy(() -> assistantService.addAdHocBot(CALLER, new AddBotRequest(URL)))
        .isInstanceOf(ConflictException.class);
    verify(providerClient, never()).sendBot(any(), any(), any());
  }

  @Test
  void addAdHocBotLetsTheSameMeetingThroughWhenItsBotIsAlreadyRunning() {
    allowed();
    running("abc-defg-hij", "ddd-eeee-fff");
    when(providerClient.sendBot("google_meet", "abc-defg-hij", URL))
        .thenReturn(new ProviderBotResponse("bot-1", "requested", "New meeting bot"));

    assistantService.addAdHocBot(CALLER, new AddBotRequest(URL));

    verify(providerClient).sendBot("google_meet", "abc-defg-hij", URL);
  }

  @Test
  void addAdHocBotRefusesWhenTheRunningBotsAreUnknown() {
    allowed();
    when(providerClient.getRunningBots()).thenReturn(new ProviderBotStatusResponse(null));

    assertThatThrownBy(() -> assistantService.addAdHocBot(CALLER, new AddBotRequest(URL)))
        .isInstanceOf(IllegalStateException.class);
    verify(providerClient, never()).sendBot(any(), any(), any());
  }

  @Test
  void addBotSendsTheBotToTheCheckedCodeNotTheLinkAsTyped() {
    String lookalike = "https://meet.google.com.example.test/abc-defg-hij?x=1";
    when(providerClient.sendBot("google_meet", "abc-defg-hij", URL))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    assistantService.addBot(new AddBotRequest(lookalike));

    verify(providerClient).sendBot("google_meet", "abc-defg-hij", URL);
  }

  @Test
  void addBotParsesUrlAndMapsProviderResponse() {
    String url = "https://meet.google.com/abc-defg-hij";
    when(providerClient.sendBot("google_meet", "abc-defg-hij", url))
        .thenReturn(new ProviderBotResponse("bot-1", "joining", "Mentor Bot"));

    var response = assistantService.addBot(new AddBotRequest(url));

    assertThat(response.id()).isEqualTo("bot-1");
    assertThat(response.platform()).isEqualTo("google_meet");
    assertThat(response.nativeMeetingId()).isEqualTo("abc-defg-hij");
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
  void getTranscriptCountsAbsoluteStartsFromTheFirstSegment() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Arya", "Başlıyoruz", 1_791_470_023.135),
            new ProviderTranscriptSegment("s2", "Arya", "Devam", 1_791_470_098.4)
        )));

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).extracting(TranscriptSegmentResponse::at)
        .containsExactly("00:00", "01:15");
  }

  @Test
  void getTranscriptReturnsEmptyListWhenSegmentsMissing() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of()));

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).isEmpty();
  }

  @Test
  void generateMeetingReportForACallerUsesTheProviderTranscriptAndTheConfiguredModel() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new ProviderTranscriptResponse(List.of(
            new ProviderTranscriptSegment("s1", "Ayşe", "Merhaba", 65.0)
        )));
    when(meetingReportService.generate(any())).thenReturn(report("Original"));
    when(accessService.requireAccess(CALLER, "Google_Meet", " ABC-DEFG-HIJ "))
        .thenReturn(new MeetingUrlParser.Parsed("google_meet", "abc-defg-hij"));

    assistantService.generateMeetingReport(CALLER, "Google_Meet", " ABC-DEFG-HIJ ");

    verify(meetingReportService).generate(List.of(
        new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "01:05")));
  }

  @Test
  void aSwitchedOffReportIsRefusedAfterTheAccessCheckWithoutFetchingTheTranscript() {
    allowed();
    ReflectionTestUtils.setField(
        assistantService, "aiFeatures", TestAiFeatures.allOnExcept(AiFeature.MEETING_REPORT));

    assertThatThrownBy(() -> assistantService.generateMeetingReport(CALLER, "google_meet", "abc-defg-hij"))
        .isInstanceOf(AiFeatureDisabledException.class);
    verify(providerClient, never()).getTranscript(any(), any());
    verify(meetingReportService, never()).generate(any());
  }

  @Test
  void generateMeetingReportForTheWatchdogUsesItsCleanedSegments() {
    when(meetingReportService.generate(any())).thenReturn(report("Cleaned"));
    var cleaned = new TranscriptSegmentResponse("s1", "Ayse Kaya", "Temiz metin", "00:10");

    assistantService.generateMeetingReport(List.of(cleaned));

    verify(providerClient, never()).getTranscript(any(), any());
    verify(meetingReportService).generate(List.of(cleaned));
  }

  @Test
  void aCallerWhoMayNotUseTheMeetingReachesNeitherTheProviderNorTheReport() {
    var forbidden = new ResponseStatusException(HttpStatus.FORBIDDEN, "no");
    doThrow(forbidden).when(accessService).requireAccess(CALLER, "google_meet", "abc-defg-hij");

    assertThatThrownBy(() -> assistantService.getTranscript(CALLER, "google_meet", "abc-defg-hij"))
        .isSameAs(forbidden);
    assertThatThrownBy(() -> assistantService.stopBot(CALLER, "google_meet", "abc-defg-hij"))
        .isSameAs(forbidden);
    assertThatThrownBy(() -> assistantService.generateMeetingReport(CALLER, "google_meet", "abc-defg-hij"))
        .isSameAs(forbidden);
    assertThatThrownBy(() -> assistantService.addAdHocBot(CALLER, new AddBotRequest(URL)))
        .isSameAs(forbidden);

    verify(providerClient, never()).getTranscript(any(), any());
    verify(providerClient, never()).stopBot(any(), any());
    verify(providerClient, never()).sendBot(any(), any(), any());
    verify(providerClient, never()).getRunningBots();
    verify(meetingReportService, never()).generate(any());
  }

  @Test
  void anUnknownMeetingIsNotSentToTheProvider() {
    doThrow(new ResourceNotFoundException("Meeting not found."))
        .when(accessService).requireAccess(CALLER, "google_meet", "zzz-zzzz-zzz");

    assertThatThrownBy(() -> assistantService.getTranscript(CALLER, "google_meet", "zzz-zzzz-zzz"))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(providerClient, never()).getTranscript(any(), any());
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
