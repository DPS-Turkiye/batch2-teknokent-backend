package com.dps.teknokent.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonSchemaUtils;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MeetingReportServiceTest {

  @Mock
  private AiProviderClient aiProviderClient;

  private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

  private MeetingReportService service() {
    return new MeetingReportService(
        aiProviderClient,
        new LocalPiiRedactionService(),
        jsonUtils,
        new JsonSchemaUtils(jsonUtils));
  }

  @Test
  void masksContactDetailsBeforeSendingTheTranscriptToTheAiProvider() {
    when(aiProviderClient.resolveReportModel(null)).thenReturn("openai/gpt-4o-mini");
    when(aiProviderClient.generateMeetingReport(any(), any(), eq(null)))
        .thenReturn(jsonUtils.readTree("{\"title\":\"Sprint\",\"summary\":\"Ozet\"}"));

    service().generate(
        List.of(new TranscriptSegmentResponse("s1", "Ayşe", "Bana ada@example.com adresinden yaz", "00:10")),
        null);

    ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
    verify(aiProviderClient).generateMeetingReport(transcript.capture(), any(), eq(null));
    assertThat(transcript.getValue()).doesNotContain("ada@example.com");
    assertThat(transcript.getValue()).contains("Ayşe", "adresinden yaz");
  }

  @Test
  void rejectsEmptyTranscriptWithoutCallingTheAiProvider() {
    assertThatThrownBy(() -> service().generate(List.of(), null))
        .isInstanceOf(InvalidRequestException.class);

    verify(aiProviderClient, never()).generateMeetingReport(any(), any(), any());
  }
}
