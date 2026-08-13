package com.dps.teknokent.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dps.teknokent.dto.provider.ProviderBotResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptResponse;
import com.dps.teknokent.dto.provider.ProviderTranscriptSegment;
import com.dps.teknokent.dto.requests.AddBotRequest;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

  @Mock
  private MeetingProviderClient providerClient;

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
    assertThat(response.nativeMeetingId()).isEqualTo("abc-defg-hij");
    assertThat(response.status()).isEqualTo("joining");
    assertThat(response.title()).isEqualTo("Mentor Bot");
    verify(providerClient).sendBot("google_meet", "abc-defg-hij", url);
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
}
