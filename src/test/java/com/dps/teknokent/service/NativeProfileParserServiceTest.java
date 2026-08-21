package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
class NativeProfileParserServiceTest {

    @Mock
    private PdfDocumentTextExtractor textExtractor;

    @Mock
    private LocalPiiRedactionService piiRedactionService;

    @Mock
    private AiProviderClient aiProviderClient;

    @Spy
    private JsonUtils jsonUtils = new JsonUtils();

    private NativeProfileParserService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new NativeProfileParserService(textExtractor, piiRedactionService, aiProviderClient, jsonUtils);
    }

    @Test
    void parse_buildsStructureFromExtractionRedactionAndAiResponse() {
        byte[] pdfBytes = "sample-pdf".getBytes();
        ExtractionResult extraction = new ExtractionResult("Acme pitch deck", 2, List.of(1), true);
        RedactionResult redaction = new RedactionResult("Acme pitch deck", Map.of(), 0);
        JsonNode profile = jsonUtils.readTree("{\"startup_name\":\"Acme\",\"stage\":\"Seed\"}");

        when(textExtractor.extract(pdfBytes, "auto")).thenReturn(extraction);
        when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);
        when(aiProviderClient.structuredProfile(eq("mentee"), eq(redaction.text()), any(JsonNode.class)))
                .thenReturn(profile);

        var result = service.parse(pdfBytes, "mentee", "auto");

        assertThat(result.profile()).isEqualTo(profile);
        assertThat(result.redactionSummary().get("mode").asText()).isEqualTo("local-before-ai");
        assertThat(result.redactionSummary().get("total_redacted").asInt()).isZero();
        assertThat(result.redactionSummary().get("redacted_by_type").isObject()).isTrue();
        assertThat(result.ocrSummary().get("mode").asText()).isEqualTo("auto");
        assertThat(result.ocrSummary().get("used").asBoolean()).isTrue();
        assertThat(result.ocrSummary().get("page_count").asInt()).isEqualTo(2);
        assertThat(result.ocrSummary().get("ocr_pages").isArray()).isTrue();

        ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiProviderClient).structuredProfile(eq("mentee"), eq(redaction.text()), schemaCaptor.capture());

        JsonNode schema = schemaCaptor.getValue();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").has("startup_name")).isTrue();
        JsonNode startupNameSchema = schema.get("properties").get("startup_name");
        assertThat(startupNameSchema.get("type").isArray()).isTrue();
        assertThat(startupNameSchema.get("type").toString()).contains("string");
    }

    @Test
    void parse_rejectsUnsupportedProfileType() {
        byte[] pdfBytes = "sample-pdf".getBytes();
        ExtractionResult extraction = new ExtractionResult("Some text", 1, List.of(), false);
        RedactionResult redaction = new RedactionResult("Some text", Map.of(), 0);

        when(textExtractor.extract(pdfBytes, "off")).thenReturn(extraction);
        when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);

        assertThatThrownBy(() -> service.parse(pdfBytes, "unknown", "off"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported profile type: unknown");
    }
}
