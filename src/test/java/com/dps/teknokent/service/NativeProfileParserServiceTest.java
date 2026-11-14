package com.dps.teknokent.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
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

import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.domain.ProfileType;
import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NativeProfileParserServiceTest {

	@Mock
	private PdfDocumentTextExtractor textExtractor;

	@Mock
	private LocalPiiRedactionService piiRedactionService;

	@Mock
	private AiProviderClient aiProviderClient;

	@Spy
	private JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

	private NativeProfileParserService service;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		service = new NativeProfileParserService(textExtractor, piiRedactionService, aiProviderClient, jsonUtils);
	}

	@Test
	void parse_buildsStructureFromExtractionRedactionAndAiResponse() {
		byte[] pdfBytes = "sample-pdf".getBytes(UTF_8);
		ExtractionResult extraction = new ExtractionResult("Acme pitch deck", 2, List.of(1), true);
		RedactionResult redaction = new RedactionResult("Acme pitch deck", Map.of(), 0);
		JsonNode profile = jsonUtils.readTree("{\"startup_name\":\"Acme\",\"stage\":\"Seed\"}");

		when(textExtractor.extract(pdfBytes, OcrMode.AUTO)).thenReturn(extraction);
		when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);
		when(aiProviderClient.structuredProfile(eq("mentee"), eq(redaction.text()), any(JsonNode.class)))
				.thenReturn(profile);

		var result = service.parse(pdfBytes, ProfileType.MENTEE, OcrMode.AUTO);

		assertThat(result.profile()).isEqualTo(profile);
		assertThat(result.redactionSummary().get("mode").asString()).isEqualTo("local-before-ai");
		assertThat(result.redactionSummary().get("total_redacted").asInt()).isZero();
		assertThat(result.redactionSummary().get("redacted_by_type").isObject()).isTrue();
		assertThat(result.ocrSummary().get("mode").asString()).isEqualTo("auto");
		assertThat(result.ocrSummary().get("used").asBoolean()).isTrue();
		assertThat(result.ocrSummary().get("page_count").asInt()).isEqualTo(2);
		assertThat(result.ocrSummary().get("ocr_pages").isArray()).isTrue();

		ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(aiProviderClient).structuredProfile(eq("mentee"), eq(redaction.text()), schemaCaptor.capture());

		JsonNode schema = schemaCaptor.getValue();
		assertThat(schema.get("type").asString()).isEqualTo("object");
		assertThat(schema.get("properties").has("startup_name")).isTrue();
		JsonNode startupNameSchema = schema.get("properties").get("startup_name");
		assertThat(startupNameSchema.get("type").isArray()).isTrue();
		assertThat(startupNameSchema.get("type").toString()).contains("string");
	}

	@Test
	void parse_asksForAMentorsCompanyAndPastRoles() {
		byte[] pdfBytes = "sample-pdf".getBytes(UTF_8);
		ExtractionResult extraction = new ExtractionResult("Mentor CV", 1, List.of(), false);
		RedactionResult redaction = new RedactionResult("Mentor CV", Map.of(), 0);
		when(textExtractor.extract(pdfBytes, OcrMode.AUTO)).thenReturn(extraction);
		JsonNode empty = jsonUtils.readTree("{}");
		when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);
		when(aiProviderClient.structuredProfile(eq("mentor"), eq(redaction.text()), any(JsonNode.class)))
				.thenReturn(empty);

		service.parse(pdfBytes, ProfileType.MENTOR, OcrMode.AUTO);

		ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(aiProviderClient).structuredProfile(eq("mentor"), eq(redaction.text()), schemaCaptor.capture());
		JsonNode properties = schemaCaptor.getValue().get("properties");
		assertThat(properties.has("current_company")).isTrue();
		JsonNode role = properties.get("work_history").get("items");
		assertThat(role.get("additionalProperties").asBoolean()).isFalse();
		assertThat(role.get("required").toString())
				.contains("title", "organization", "period", "summary");
	}

	@Test
	void parse_asksAMentorsCvForTheStudiesAStudentMentorFillsIn() {
		byte[] pdfBytes = "sample-pdf".getBytes(UTF_8);
		ExtractionResult extraction = new ExtractionResult("Student CV", 1, List.of(), false);
		RedactionResult redaction = new RedactionResult("Student CV", Map.of(), 0);
		when(textExtractor.extract(pdfBytes, OcrMode.AUTO)).thenReturn(extraction);
		JsonNode empty = jsonUtils.readTree("{}");
		when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);
		when(aiProviderClient.structuredProfile(eq("mentor"), eq(redaction.text()), any(JsonNode.class)))
				.thenReturn(empty);

		service.parse(pdfBytes, ProfileType.MENTOR, OcrMode.AUTO);

		ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(aiProviderClient).structuredProfile(eq("mentor"), eq(redaction.text()), schemaCaptor.capture());
		JsonNode schema = schemaCaptor.getValue();
		assertThat(schema.get("required").toString()).contains("department", "study_year", "strong_courses",
				"student_projects", "exchange_experience");
		JsonNode studyYear = schema.get("properties").get("study_year");
		assertThat(studyYear.get("type").toString()).contains("null");
		assertThat(studyYear.get("description").asString()).contains("Hazırlık", "4. sınıf", "Doktora");
		assertThat(schema.get("properties").get("strong_courses").get("type").asString()).isEqualTo("array");
		JsonNode school = schema.get("properties").get("education").get("items");
		assertThat(school.get("required").toString()).contains("school", "degree", "period");
		assertThat(schema.get("properties").get("work_history").get("description").asString())
				.contains("internships", "part-time", "club", "volunteer");
	}

	@Test
	void parse_asksAnIndividualsCvForTheirRoleAndGoalsUnderTheIndividualSchemaName() {
		byte[] pdfBytes = "sample-pdf".getBytes(UTF_8);
		ExtractionResult extraction = new ExtractionResult("Kişisel CV", 1, List.of(), false);
		RedactionResult redaction = new RedactionResult("Kişisel CV", Map.of(), 0);
		when(textExtractor.extract(pdfBytes, OcrMode.AUTO)).thenReturn(extraction);
		JsonNode empty = jsonUtils.readTree("{}");
		when(piiRedactionService.redact(extraction.text())).thenReturn(redaction);
		when(aiProviderClient.structuredProfile(eq("individual_mentee"), eq(redaction.text()), any(JsonNode.class)))
				.thenReturn(empty);

		service.parse(pdfBytes, ProfileType.INDIVIDUAL_MENTEE, OcrMode.AUTO);

		ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
		verify(aiProviderClient).structuredProfile(eq("individual_mentee"), eq(redaction.text()),
				schemaCaptor.capture());
		assertThat(schemaCaptor.getValue().get("required").toString())
				.contains("current_role", "desired_role", "career_goals", "skills_to_develop");
	}
}
