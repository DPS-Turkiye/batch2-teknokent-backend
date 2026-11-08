package com.dps.teknokent.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ExtractSyllabusPhasesRequest;
import com.dps.teknokent.dto.responses.SyllabusPhasesResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.ProgramCoordination;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/** Reads a syllabus PDF and proposes program phases; the same local redaction as CV parsing runs first. */
@Service
@RequiredArgsConstructor
public class SyllabusPhaseService {

	static final int MAX_PHASES = 12;
	private static final int MAX_NAME_LENGTH = 120;
	private static final int MAX_OUTPUT_LENGTH = 200;
	// ponytail: a long syllabus is cut, not chunked; split it into parts if real files hit this.
	static final int MAX_TEXT_LENGTH = 60_000;

	private final CoordinatorProgramService programService;
	private final StoredDocumentRepository storedDocumentRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ObjectStorage objectStorage;
	private final PdfDocumentTextExtractor textExtractor;
	private final LocalPiiRedactionService piiRedactionService;
	private final AiProviderClient aiProviderClient;
	private final JsonUtils jsonUtils;

	public SyllabusPhasesResponse extract(UUID actorId, ExtractSyllabusPhasesRequest request) {
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		StoredDocument document = requireReadable(actor, request.documentId());
		ExtractionResult extraction = textExtractor.extract(objectStorage.get(document.getS3Key()), "auto");
		String text = piiRedactionService.redact(extraction.text()).text();
		if (text.isBlank()) {
			throw new InvalidRequestException("No text could be read from the syllabus.");
		}
		if (text.length() > MAX_TEXT_LENGTH) {
			text = text.substring(0, MAX_TEXT_LENGTH);
		}
		JsonNode result = aiProviderClient.syllabusPhases(text, request.startDate(), request.endDate(), schema());
		return new SyllabusPhasesResponse(phasesOf(result), extraction.pageCount());
	}

	/** The actor's own upload, or the syllabus of a program they run. Anything else reads as missing. */
	private StoredDocument requireReadable(AppUser actor, UUID documentId) {
		return storedDocumentRepository.findById(documentId)
				.filter(document -> DocumentKind.SYLLABUS.value().equals(document.getKind()))
				.filter(document -> actor.getId().equals(document.getUserId()) || runsProgramWith(actor, documentId))
				.orElseThrow(() -> new ResourceNotFoundException("Syllabus not found: " + documentId));
	}

	private boolean runsProgramWith(AppUser actor, UUID documentId) {
		return programRepository.findBySyllabusDocumentId(documentId).stream()
				.anyMatch(program -> actor.has(UserRole.ADMIN)
						|| ProgramCoordination.coordinates(program, actor.getId(), programCoordinatorRepository));
	}

	static List<SyllabusPhasesResponse.Phase> phasesOf(JsonNode result) {
		List<SyllabusPhasesResponse.Phase> phases = new ArrayList<>();
		for (JsonNode node : result.path("phases")) {
			String name = cut(node.path("name").asText(""), MAX_NAME_LENGTH);
			if (name == null) {
				continue;
			}
			LocalDate start = dateOf(node.path("start_date"));
			LocalDate end = dateOf(node.path("end_date"));
			if (start != null && end != null && end.isBefore(start)) {
				end = null;
			}
			phases.add(new SyllabusPhasesResponse.Phase(
					name, cut(node.path("expected_output").asText(""), MAX_OUTPUT_LENGTH), start, end));
			if (phases.size() == MAX_PHASES) {
				break;
			}
		}
		return phases;
	}

	private static String cut(String value, int maxLength) {
		String trimmed = value.trim();
		if (trimmed.isEmpty() || "null".equals(trimmed)) {
			return null;
		}
		return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() : trimmed;
	}

	private static LocalDate dateOf(JsonNode node) {
		if (!node.isTextual()) {
			return null;
		}
		try {
			return LocalDate.parse(node.asText().trim());
		} catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	private JsonNode schema() {
		ObjectNode phase = object();
		ObjectNode phaseProperties = phase.putObject("properties");
		phaseProperties.set("name", jsonUtils.createObjectNode().put("type", "string"));
		phaseProperties.set("expected_output", nullable("string"));
		phaseProperties.set("start_date", nullable("string"));
		phaseProperties.set("end_date", nullable("string"));
		require(phase, "name", "expected_output", "start_date", "end_date");

		ObjectNode root = object();
		ObjectNode properties = root.putObject("properties");
		properties.putObject("phases").put("type", "array").set("items", phase);
		properties.putObject("warnings").put("type", "array").putObject("items").put("type", "string");
		require(root, "phases", "warnings");
		return root;
	}

	private ObjectNode object() {
		return jsonUtils.createObjectNode().put("type", "object").put("additionalProperties", false);
	}

	private ObjectNode nullable(String type) {
		ObjectNode schema = jsonUtils.createObjectNode();
		schema.putArray("type").add(type).add("null");
		return schema;
	}

	private static void require(ObjectNode schema, String... names) {
		ArrayNode required = schema.putArray("required");
		for (String name : names) {
			required.add(name);
		}
	}
}
