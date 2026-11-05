package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ExtractSyllabusPhasesRequest;
import com.dps.teknokent.dto.responses.SyllabusPhasesResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SyllabusPhaseServiceTest {

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final LocalDate START = LocalDate.of(2026, 11, 3);
	private static final LocalDate END = LocalDate.of(2027, 9, 30);

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Mock
	private ObjectStorage objectStorage;
	@Mock
	private PdfDocumentTextExtractor textExtractor;
	@Mock
	private LocalPiiRedactionService piiRedactionService;
	@Mock
	private AiProviderClient aiProviderClient;

	private SyllabusPhaseService service;

	@BeforeEach
	void setUp() {
		service = new SyllabusPhaseService(programService, storedDocumentRepository, programRepository,
				programCoordinatorRepository, objectStorage, textExtractor, piiRedactionService, aiProviderClient,
				jsonUtils);
	}

	@Test
	void proposesPhasesFromTheRedactedSyllabusWithTheProgramDates() {
		whenActorIs(COORDINATOR_ID, UserRole.COORDINATOR);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(syllabus(COORDINATOR_ID)));
		stubText("Hafta 1-4: Keşif. Sorumlu: ali@example.com", "Hafta 1-4: Keşif. Sorumlu: [EMAIL]", 18);
		when(aiProviderClient.syllabusPhases(anyString(), any(), any(), any())).thenReturn(json("""
				{"phases": [{"name": "Keşif", "expected_output": "Problem doğrulama raporu",
				  "start_date": "2026-11-03", "end_date": "2026-11-30"}], "warnings": []}
				"""));

		SyllabusPhasesResponse response = service.extract(COORDINATOR_ID, request());

		assertThat(response.pageCount()).isEqualTo(18);
		assertThat(response.phases()).containsExactly(new SyllabusPhasesResponse.Phase(
				"Keşif", "Problem doğrulama raporu", START, LocalDate.of(2026, 11, 30)));
		verify(aiProviderClient).syllabusPhases(eq("Hafta 1-4: Keşif. Sorumlu: [EMAIL]"), eq(START), eq(END), any());
	}

	@Test
	void aVeryLongSyllabusIsCutBeforeTheAiCall() {
		whenActorIs(COORDINATOR_ID, UserRole.COORDINATOR);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(syllabus(COORDINATOR_ID)));
		String longText = "a".repeat(SyllabusPhaseService.MAX_TEXT_LENGTH + 500);
		stubText(longText, longText, 40);
		when(aiProviderClient.syllabusPhases(anyString(), any(), any(), any())).thenReturn(json("{\"phases\": []}"));

		service.extract(COORDINATOR_ID, request());

		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(aiProviderClient).syllabusPhases(sent.capture(), any(), any(), any());
		assertThat(sent.getValue()).hasSize(SyllabusPhaseService.MAX_TEXT_LENGTH);
	}

	@Test
	void aSyllabusWithNoReadableTextIsRefusedBeforeTheAiCall() {
		whenActorIs(COORDINATOR_ID, UserRole.COORDINATOR);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(syllabus(COORDINATOR_ID)));
		stubText("", "", 2);

		assertThatThrownBy(() -> service.extract(COORDINATOR_ID, request()))
				.isInstanceOf(InvalidRequestException.class);
		verify(aiProviderClient, never()).syllabusPhases(anyString(), any(), any(), any());
	}

	@Test
	void anotherCoordinatorsUploadReadsAsMissing() {
		whenActorIs(OTHER_COORDINATOR_ID, UserRole.COORDINATOR);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(syllabus(COORDINATOR_ID)));
		when(programRepository.findBySyllabusDocumentId(DOCUMENT_ID)).thenReturn(List.of());

		assertThatThrownBy(() -> service.extract(OTHER_COORDINATOR_ID, request()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(objectStorage, never()).get(anyString());
	}

	@Test
	void aCoCoordinatorCanReExtractTheirProgramsSyllabus() {
		whenActorIs(OTHER_COORDINATOR_ID, UserRole.COORDINATOR);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(syllabus(COORDINATOR_ID)));
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setCreatedBy(COORDINATOR_ID);
		when(programRepository.findBySyllabusDocumentId(DOCUMENT_ID)).thenReturn(List.of(program));
		when(programCoordinatorRepository.existsByProgramIdAndUserId(program.getId(), OTHER_COORDINATOR_ID))
				.thenReturn(true);
		stubText("Faz 1", "Faz 1", 1);
		when(aiProviderClient.syllabusPhases(anyString(), any(), any(), any())).thenReturn(json("{\"phases\": []}"));

		assertThat(service.extract(OTHER_COORDINATOR_ID, request()).phases()).isEmpty();
	}

	@Test
	void aCvIsNotASyllabus() {
		whenActorIs(COORDINATOR_ID, UserRole.COORDINATOR);
		StoredDocument cv = syllabus(COORDINATOR_ID);
		cv.setKind(DocumentKind.CV.value());
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(cv));

		assertThatThrownBy(() -> service.extract(COORDINATOR_ID, request()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void theAiAnswerIsCleanedBeforeTheCoordinatorSeesIt() {
		JsonNode answer = json("""
				{"phases": [
				  {"name": "  Keşif  ", "expected_output": "  ", "start_date": "3 Kasım", "end_date": null},
				  {"name": " ", "expected_output": "dropped", "start_date": null, "end_date": null},
				  {"name": "MVP", "expected_output": "%s", "start_date": "2027-01-31", "end_date": "2026-12-01"}
				]}
				""".formatted("x".repeat(250)));

		List<SyllabusPhasesResponse.Phase> phases = SyllabusPhaseService.phasesOf(answer);

		assertThat(phases).hasSize(2);
		assertThat(phases.get(0)).isEqualTo(new SyllabusPhasesResponse.Phase("Keşif", null, null, null));
		assertThat(phases.get(1).name()).isEqualTo("MVP");
		assertThat(phases.get(1).expectedOutput()).hasSize(200);
		assertThat(phases.get(1).startDate()).isEqualTo(LocalDate.of(2027, 1, 31));
		assertThat(phases.get(1).endDate()).as("an end before the start is dropped, not trusted").isNull();
	}

	@Test
	void atMostTwelvePhasesAreProposed() {
		String phases = String.join(",", IntStream.rangeClosed(1, 15)
				.mapToObj(n -> "{\"name\": \"Faz " + n + "\"}")
				.toList());

		assertThat(SyllabusPhaseService.phasesOf(json("{\"phases\": [" + phases + "]}")))
				.hasSize(SyllabusPhaseService.MAX_PHASES)
				.last().extracting(SyllabusPhasesResponse.Phase::name).isEqualTo("Faz 12");
	}

	private void whenActorIs(UUID id, UserRole role) {
		AppUser actor = new AppUser();
		actor.setId(id);
		actor.setRole(role);
		when(programService.requireCoordinatorOrAdmin(id)).thenReturn(actor);
	}

	private void stubText(String raw, String redacted, int pages) {
		when(objectStorage.get("syllabus-key")).thenReturn(new byte[] { 1 });
		when(textExtractor.extract(any(), eq("auto"))).thenReturn(new ExtractionResult(raw, pages, List.of(), false));
		when(piiRedactionService.redact(raw)).thenReturn(new RedactionResult(redacted, Map.of(), 0));
	}

	private static StoredDocument syllabus(UUID ownerId) {
		StoredDocument document = new StoredDocument();
		document.setId(DOCUMENT_ID);
		document.setUserId(ownerId);
		document.setKind(DocumentKind.SYLLABUS.value());
		document.setS3Key("syllabus-key");
		return document;
	}

	private static ExtractSyllabusPhasesRequest request() {
		return new ExtractSyllabusPhasesRequest(null, DOCUMENT_ID, START, END);
	}

	private JsonNode json(String value) {
		return jsonUtils.readTree(value);
	}
}
