package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TechnologyInferenceServiceTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final TechnologyInferenceService service = new TechnologyInferenceService();

	@Test
	void recoversExplicitTechnologiesAndInfersFullStackFromFrontendAndBackendEvidence() throws Exception {
		ObjectNode profile = (ObjectNode) objectMapper.readTree("""
				{
				  "professional_summary": "Built a web application for hospital workflows.",
				  "technologies": [],
				  "projects": [
				    {"quote": "Developed the React frontend and user interface."},
				    {"quote": "Implemented backend REST API services with Spring Boot and PostgreSQL."}
				  ]
				}
				""");

		ObjectNode enriched = service.enrich(profile);

		assertThat(textValues(enriched.path("technologies")))
				.contains("React", "Spring Boot", "PostgreSQL", "Web application development",
						"Frontend development", "Backend development", "Full-stack development");
		assertThat(enriched.path("technology_inferences").toString())
				.contains("EXPLICIT_RECOVERY", "DERIVED_CAPABILITY", "evidence");
	}

	@Test
	void webApplicationAloneDoesNotOverclaimFullStackExperience() throws Exception {
		ObjectNode profile = (ObjectNode) objectMapper.readTree("""
				{
				  "professional_summary": "Built a web application for appointment scheduling.",
				  "technologies": []
				}
				""");

		ObjectNode enriched = service.enrich(profile);

		assertThat(textValues(enriched.path("technologies")))
				.contains("Web application development")
				.doesNotContain("Full-stack development");
	}

	@Test
	void preservesExistingTechnologyWithoutDuplicatingIt() throws Exception {
		ObjectNode profile = (ObjectNode) objectMapper.readTree("""
				{
				  "professional_summary": "Python services and model monitoring in production.",
				  "technologies": ["Python"]
				}
				""");

		ObjectNode enriched = service.enrich(profile);

		assertThat(textValues(enriched.path("technologies"))).containsOnlyOnce("Python");
		assertThat(textValues(enriched.path("technologies"))).contains("MLOps");
	}

	private List<String> textValues(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(item -> values.add(item.asText()));
		return values;
	}
}
