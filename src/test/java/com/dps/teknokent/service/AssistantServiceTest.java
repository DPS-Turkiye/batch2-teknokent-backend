package com.dps.teknokent.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.dto.requests.AddBotRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class) //mochito yu açar
class AssistantServiceTest {

    //sahte dış servis
  @Mock
  private MeetingProviderClient providerClient;

  @InjectMocks
  private AssistantService assistantService;  // gerçek service; mock client enjekte edilir
//Ne ispatlar: Link parse + provider cevabını API cevabına çevirme.
  @Test
  void addBotParsesUrlAndMapsProviderResponse() {
    String url = "https://meet.google.com/abc-defg-hij"; //bir meet url verilir
    //sendBot(...) çağrılınca sahte cevap döner: id, status, title.
    when(providerClient.sendBot("google_meet", "abc-defg-hij", url))
        .thenReturn(Map.of(
            "id", "bot-1",
            "status", "joining",
            "title", "Mentor Bot"
        ));
//add bot çalıştı
    var response = assistantService.addBot(new AddBotRequest(url));

    assertThat(response.id()).isEqualTo("bot-1");
    assertThat(response.platform()).isEqualTo("google_meet");
    assertThat(response.nativeMeetingId()).isEqualTo("abc-defg-hij");
    assertThat(response.status()).isEqualTo("joining");
    assertThat(response.title()).isEqualTo("Mentor Bot");
    //sendBot doğru argümanlarla çağılmış (verify)
    verify(providerClient).sendBot("google_meet", "abc-defg-hij", url);
  }
//Ne ispatlar: Kötü linkte dış servise istek atılmaz.
  @Test
  void addBotRejectsBadUrlWithoutCallingProvider() {
    //Zoom linki verilir (desteklenmiyor),IllegalArgumentException beklenir.
    assertThatThrownBy(() -> assistantService.addBot(new AddBotRequest("https://zoom.us/j/1")))
        .isInstanceOf(IllegalArgumentException.class);
//sendBot hiç çağrılmamalı (never).
    verify(providerClient, never()).sendBot(any(), any(), any());
  }
//Ne ispatlar: Transcript dönüşümü ve formatTime
  @Test
  void getTranscriptMapsSegmentsAndFormatsTime() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(Map.of(
            "segments", List.of(
                Map.of(
                    "segment_id", "s1",
                    "speaker", "Ayşe",
                    "text", "Merhaba",
                    "start", 65
                )
            )
        ));

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).hasSize(1);
    assertThat(response.segments().get(0).id()).isEqualTo("s1");
    assertThat(response.segments().get(0).speaker()).isEqualTo("Ayşe");
    assertThat(response.segments().get(0).text()).isEqualTo("Merhaba");
    assertThat(response.segments().get(0).at()).isEqualTo("01:05");
  }
//Ne ispatlar: Toplantı yeni / henüz konuşma yokken güvenli davranış.
  @Test
  void getTranscriptReturnsEmptyListWhenSegmentsMissing() {
    when(providerClient.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(Map.of());

    var response = assistantService.getTranscript("google_meet", "abc-defg-hij");

    assertThat(response.segments()).isEmpty();
  }
}