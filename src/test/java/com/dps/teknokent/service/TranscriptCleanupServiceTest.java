package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonSchemaUtils;
import com.dps.teknokent.util.JsonUtils;

@ExtendWith(MockitoExtension.class)
class TranscriptCleanupServiceTest {

	@Mock
	private AiProviderClient aiProviderClient;

	private final JsonUtils jsonUtils = new JsonUtils();

	private final List<TranscriptSegmentResponse> raw = List.of(
			new TranscriptSegmentResponse("s1", "Arya", "we shouldn't use Lintal", "00:00"),
			new TranscriptSegmentResponse("s2", "Arya", "bana arya@tekno.co adresinden yaz", "00:12"),
			new TranscriptSegmentResponse("s3", "Deniz", "tamam", "00:20"));

	private TranscriptCleanupService service() {
		return service(TestAiFeatures.allOn());
	}

	private TranscriptCleanupService service(AiFeatures aiFeatures) {
		return new TranscriptCleanupService(
				aiProviderClient, new LocalPiiRedactionService(), new JsonSchemaUtils(jsonUtils), aiFeatures);
	}

	@Test
	void sendsNothingOutWhenMeetingReportsAreSwitchedOff() {
		List<TranscriptSegmentResponse> kept = service(TestAiFeatures.allOnExcept(AiFeature.MEETING_REPORT)).clean(raw);

		assertThat(kept).isEqualTo(raw);
		verifyNoInteractions(aiProviderClient);
	}

	@Test
	void appliesTheCorrectionsAndKeepsLinesWithPersonalDataOnTheServer() {
		when(aiProviderClient.cleanTranscript(anyString(), any())).thenReturn(jsonUtils.readTree("""
				{"language": "en", "segments": [
				  {"id": "s1", "text": "We shouldn't use Linear."},
				  {"id": "s3", "text": "Tamam."}]}
				"""));

		List<TranscriptSegmentResponse> cleaned = service().clean(raw);

		assertThat(cleaned).extracting(TranscriptSegmentResponse::text)
				.containsExactly("We shouldn't use Linear.", "bana arya@tekno.co adresinden yaz", "Tamam.");
		assertThat(cleaned).extracting(TranscriptSegmentResponse::at).containsExactly("00:00", "00:12", "00:20");
		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(aiProviderClient).cleanTranscript(sent.capture(), any());
		assertThat(sent.getValue()).isEqualTo("s1\twe shouldn't use Lintal\ns3\ttamam");
	}

	@Test
	void keepsTheRawTranscriptWhenTheProviderFailsOrSendsPlaceholders() {
		when(aiProviderClient.cleanTranscript(anyString(), any()))
				.thenThrow(new InvalidRequestException("down"))
				.thenReturn(jsonUtils.readTree("""
						{"language": "tr", "segments": [{"id": "s1", "text": "[REDACTED_EMAIL] yaz"}]}
						"""));

		assertThat(service().clean(raw)).isEqualTo(raw);
		assertThat(service().clean(raw)).isEqualTo(raw);
	}

	@Test
	void sendsNothingForAnEmptyTranscript() {
		assertThat(service().clean(List.of())).isEmpty();
		verifyNoInteractions(aiProviderClient);
	}
}
