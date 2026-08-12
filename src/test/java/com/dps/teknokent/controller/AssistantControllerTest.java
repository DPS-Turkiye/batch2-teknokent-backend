package com.dps.teknokent.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dps.teknokent.config.SecurityConfig;
import com.dps.teknokent.dto.requests.AddBotRequest;
import com.dps.teknokent.dto.responses.AddBotResponse;
import com.dps.teknokent.dto.responses.TranscriptResponse;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.service.AssistantService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//“HTTP isteği gelince controller doğru status/JSON mu dönüyor?”
@WebMvcTest(AssistantController.class) // sadece asistantcontroller ı test, sadece uygulamanın web katmanını açar,servis fln yüklenmez controller ı kontrol için
@Import(SecurityConfig.class) // /api/assistant/** permitAll için
class AssistantControllerTest {

  @Autowired
  private MockMvc mockMvc; // sahte HTTP istemcisi,Spring MVC’ye içeriden sahte HTTP isteği atar:

  @MockitoBean  //Controller’ın constructor’ında AssistantService var. Bean olmazsa context ayağa kalkmaz.
  private AssistantService assistantService; // service sahte; gerçek logic yok onu zaten test ettik

  @Test
  void addBotReturnsCreated() throws Exception {
    //ervice addBot çağrılınca hazır AddBotResponse döner.
    when(assistantService.addBot(any(AddBotRequest.class)))
        .thenReturn(new AddBotResponse(
            "bot-1",
            "google_meet",
            "abc-defg-hij",
            "joining",
            "Mentor Bot"
        ));
//Gerçek HTTP gibi: POST /api/assistant/bots + JSON body.
    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("bot-1"))
        .andExpect(jsonPath("$.platform").value("google_meet"))
        .andExpect(jsonPath("$.nativeMeetingId").value("abc-defg-hij"));
  }
//İspat: Controller’daki catch (IllegalArgumentException) → 400 + ErrorResponse.
//service hata atınca controller 400 + detail döner mi?
  @Test
  void addBotReturnsBadRequestOnInvalidUrl() throws Exception {
    when(assistantService.addBot(any(AddBotRequest.class)))
        .thenThrow(new IllegalArgumentException("Desteklenmeyen toplantı linki"));

    mockMvc.perform(post("/api/assistant/bots")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"meetingUrl\":\"https://zoom.us/j/1\"}"))  // JSON body’de Zoom linki.
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Desteklenmeyen toplantı linki"));
  }
//İspat: Path variable’lar service’e gider, cevap JSON’a yazılır.
  @Test
  void getTranscriptReturnsOk() throws Exception {
    //Service hazır segment listesi döner.
    when(assistantService.getTranscript("google_meet", "abc-defg-hij"))
        .thenReturn(new TranscriptResponse(List.of(
            new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "01:05")
        )));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.segments[0].speaker").value("Ayşe"))
        .andExpect(jsonPath("$.segments[0].text").value("Merhaba"))
        .andExpect(jsonPath("$.segments[0].at").value("01:05"));
  }
//İspat: Controller’daki genel catch → 502 + anlamlı mesaj.
  @Test
  void getTranscriptReturnsBadGatewayOnFailure() throws Exception {
    //Service RuntimeException("provider down") fırlatır.
    when(assistantService.getTranscript("google_meet", "abc-defg-hij"))
        .thenThrow(new RuntimeException("provider down"));

    mockMvc.perform(get("/api/assistant/transcripts/google_meet/abc-defg-hij"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.detail").value("Transcript alınamadı: provider down"));
  }
}