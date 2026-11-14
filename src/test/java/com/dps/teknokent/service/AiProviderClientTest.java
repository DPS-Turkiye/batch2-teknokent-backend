package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.AiProperties;
import com.dps.teknokent.config.AiProperties.AiProvider;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.exception.AiFeatureDisabledException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiProviderClientTest {

	private static final JsonNode ANY_SCHEMA = new JsonUtils(new ObjectMapper()).readTree("{\"type\":\"object\"}");

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
	private final List<HttpServer> providers = new ArrayList<>();

	@AfterEach
	void stopProviders() {
		providers.forEach(server -> server.stop(0));
	}

	@Test
	void providerNameAndModelSelection_useOpenrouterSettings() throws Exception {
		AiProviderClient client = client(new AiProperties(AiProvider.OPENROUTER, null,
				new AiProperties.OpenRouter(null, null, "openrouter-profile", null, "openrouter-embedding", null)));

		assertThat(client.providerName()).isEqualTo("openrouter");
		assertThat(client.embeddingModel()).isEqualTo("openrouter-embedding");
	}

	@Test
	void structuredProfile_parsesStructuredContentFromProvider() throws Exception {
		HttpServer server = startServer(exchange -> {
			assertThat(exchange.getRequestMethod()).isEqualTo("POST");
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
			assertThat(exchange.getRequestHeaders().getFirst("X-Title")).isEqualTo("TeamTNT Spring Backend");

			String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(requestBody).contains("\"model\":\"openrouter-profile\"");
			assertThat(requestBody).contains("\"role\":\"user\"");

			String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"Ahmet\\\",\\\"skills\\\":[\\\"Java\\\",\\\"Spring\\\"]}\"}}]}";
			writeJson(exchange, 200, response);
		});

		try {
			AiProviderClient client = openRouter(server, null);

			JsonNode schema = jsonUtils.readTree("""
					{
					  "type": "object",
					  "properties": {
					    "name": {"type": "string"},
					    "skills": {"type": "array", "items": {"type": "string"}}
					  },
					  "required": ["name", "skills"],
					  "additionalProperties": false
					}
					""");

			JsonNode result = client.structuredProfile("candidate", "CV metni", schema);

			assertThat(result.get("name").asString()).isEqualTo("Ahmet");
			assertThat(result.get("skills").get(0).asString()).isEqualTo("Java");
			assertThat(result.get("skills").get(1).asString()).isEqualTo("Spring");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void embeddings_returnsVectorsFromProviderResponse() throws Exception {
		HttpServer server = startServer(exchange -> {
			String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(requestBody).contains("\"model\":\"openrouter-embedding\"");
			assertThat(requestBody).contains("\"input\":[\"Java\",\"Spring\"]");

			String response = """
					{
					  "data": [
					    {"embedding": [0.1, 0.2]},
					    {"embedding": [0.3, 0.4]}
					  ]
					}
					""";
			writeJson(exchange, 200, response);
		});

		try {
			AiProviderClient client = openRouter(server, null);

			List<List<Double>> result = client.embeddings(List.of("Java", "Spring"));

			assertThat(result).hasSize(2);
			assertThat(result.get(0)).containsExactly(0.1d, 0.2d);
			assertThat(result.get(1)).containsExactly(0.3d, 0.4d);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void structuredProfile_throwsWhenProviderReturnsBlankContent() throws Exception {
		HttpServer server = startServer(exchange -> {
			String response = """
					{
					  "choices": [
					    {"message": {"content": "   "}}
					  ]
					}
					""";
			writeJson(exchange, 200, response);
		});

		try {
			AiProviderClient client = openAi(server);

			JsonNode schema = jsonUtils.readTree(
					"""
							{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}
							""");

			assertThatThrownBy(() -> client.structuredProfile("candidate", "CV metni", schema))
					.isInstanceOf(InvalidRequestException.class)
					.hasMessageContaining("empty profile");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void meetingReport_retriesWithoutReasoningWhenTheModelDemandsIt() throws Exception {
		// Google models are sent reasoning:{effort:none} to keep reports cheap, but
		// newer ones (gemini-3.8-flash) reject the request outright for it. Without
		// the retry, switching OPENROUTER_REPORT_MODEL to one of those takes report
		// generation down in production with a one-line env change.
		List<String> bodies = new java.util.ArrayList<>();
		HttpServer server = startServer(exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			bodies.add(body);
			if (body.contains("\"reasoning\"")) {
				writeJson(exchange, 400,
						"{\"error\":{\"message\":\"Reasoning is mandatory for this endpoint and cannot be disabled.\"}}");
				return;
			}
			writeJson(exchange, 200,
					"{\"choices\":[{\"message\":{\"content\":\"{\\\"title\\\":\\\"Rapor\\\"}\"}}]}");
		});

		try {
			AiProviderClient client = openRouter(server, "google/gemini-3.8-flash");

			JsonNode schema = jsonUtils.readTree("{\"type\":\"object\"}");
			JsonNode report = client.generateMeetingReport("Toplanti metni", schema);

			assertThat(report.path("title").asString()).isEqualTo("Rapor");
			assertThat(bodies).hasSize(2);
			assertThat(bodies.get(0)).contains("\"reasoning\"");
			assertThat(bodies.get(1)).doesNotContain("\"reasoning\"");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void aSwitchedOffFeatureSendsNothingToTheProvider() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = startServer(exchange -> {
			requests.incrementAndGet();
			writeJson(exchange, 500, "{}");
		});

		try {
			AiProviderClient client = new AiProviderClient(jsonUtils, TestAiFeatures.allOnExcept(AiFeature.values()),
					openRouterSettings(server, "test-key", null));
			JsonNode schema = jsonUtils.readTree("{\"type\": \"object\"}");

			assertThatThrownBy(() -> client.structuredProfile("mentee", "CV metni", schema))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThatThrownBy(() -> client.syllabusPhases("Hafta 1", null, null, schema))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThatThrownBy(() -> client.generateMeetingReport("Ayşe: merhaba", schema))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThatThrownBy(() -> client.cleanTranscript("s1\tmerhaba", schema))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThatThrownBy(() -> client.taskHandover("Members:", schema))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThatThrownBy(() -> client.embeddings(List.of("Java")))
					.isInstanceOf(AiFeatureDisabledException.class);
			assertThat(requests.get()).isZero();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void weeklyProgramTellsTheModelTheProgramDatesAndHowManyWeeksItRuns() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{\"weeks\":[{\"week\":1,\"title\":\"Keşfetmeye Başla\"}]}"));
		JsonNode schema = jsonUtils.readTree("{\"type\":\"object\",\"required\":[\"weeks\"]}");

		JsonNode result = openRouter(server, "openai/gpt-4o-mini").weeklyProgram(
				"1 · Keşfetmeye Başla", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 13), schema);

		assertThat(result.path("weeks").path(0).path("title").asString()).isEqualTo("Keşfetmeye Başla");
		assertThat(requests).hasSize(1);
		JsonNode request = requests.get(0);
		assertThat(request.path("model").asString()).isEqualTo("openrouter-profile");
		assertThat(systemPrompt(request))
				.contains("Program start date: 2026-11-02. Program end date: 2026-12-13. Program weeks: 6.");
		assertThat(userText(request)).isEqualTo("1 · Keşfetmeye Başla");
		JsonNode format = request.path("response_format");
		assertThat(format.path("type").asString()).isEqualTo("json_schema");
		assertThat(format.path("json_schema").path("name").asString()).isEqualTo("weekly_program");
		assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
		assertThat(format.path("json_schema").path("schema")).isEqualTo(schema);
	}

	@Test
	void syllabusPhasesTellsTheModelTheProgramDatesAndHowManyWeeksItRuns() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{\"phases\":[{\"name\":\"Keşif\"}]}"));

		JsonNode result = openRouter(server, "openai/gpt-4o-mini").syllabusPhases(
				"Hafta 1-4: Keşif", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 10), ANY_SCHEMA);

		assertThat(result.path("phases").path(0).path("name").asString()).isEqualTo("Keşif");
		assertThat(requests).hasSize(1);
		// Nine days are two program weeks: the partial last week counts.
		assertThat(systemPrompt(requests.get(0)))
				.contains("Program start date: 2026-11-02. Program end date: 2026-11-10. Program weeks: 2.");
		assertThat(userText(requests.get(0))).isEqualTo("Hafta 1-4: Keşif");
		assertThat(requests.get(0).path("response_format").path("json_schema").path("name").asString())
				.isEqualTo("syllabus_phases");
	}

	@Test
	void aProgramWithoutDatesIsCalledUnknownInsteadOfBeingGuessed() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{}"));
		AiProviderClient client = openRouter(server, "openai/gpt-4o-mini");

		client.weeklyProgram("Hafta 1", null, null, ANY_SCHEMA);
		client.syllabusPhases("Hafta 1", null, null, ANY_SCHEMA);
		client.syllabusPhases("Hafta 1", LocalDate.of(2026, 11, 2), null, ANY_SCHEMA);

		assertThat(requests).hasSize(3);
		assertThat(systemPrompt(requests.get(0)))
				.contains("Program start date: unknown. Program end date: unknown. Program weeks: unknown.");
		assertThat(systemPrompt(requests.get(1)))
				.contains("Program start date: unknown. Program end date: unknown. Program weeks: unknown.");
		assertThat(systemPrompt(requests.get(2)))
				.contains("Program start date: 2026-11-02. Program end date: unknown. Program weeks: unknown.");
	}

	@Test
	void aStructuredAnswerThatIsNotJsonIsRefused() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(), answer("Üzgünüm, bu dosyayı okuyamadım."));
		AiProviderClient client = openRouter(server, "openai/gpt-4o-mini");

		assertThatThrownBy(() -> client.weeklyProgram("Hafta 1", null, null, ANY_SCHEMA))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The AI provider returned an invalid structured weekly program.");
	}

	@Test
	void aProviderAnswerWithoutAnyChoiceCountsAsEmpty() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(), new Reply(200, "{\"choices\":[]}"),
				new Reply(200, ""));
		AiProviderClient client = openRouter(server, "openai/gpt-4o-mini");

		assertThatThrownBy(() -> client.syllabusPhases("Hafta 1", null, null, ANY_SCHEMA))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The AI provider returned an empty phase list.");
		assertThatThrownBy(() -> client.structuredProfile("mentee", "CV metni", ANY_SCHEMA))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The AI provider returned an empty profile.");
	}

	@Test
	void nothingIsSentWithoutAnApiKeyAndTheCallerGetsABadGateway() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{}"));
		AiProviderClient openAi = client(new AiProperties(AiProvider.OPENAI,
				new AiProperties.OpenAi(" ", baseUrl(server), null, null), null));
		AiProviderClient openRouter = client(openRouterSettings(server, null, "openai/gpt-4o-mini"));

		assertBadGateway(() -> openAi.structuredProfile("mentee", "CV metni", ANY_SCHEMA));
		assertBadGateway(() -> openRouter.embeddings(List.of("Java")));
		assertThat(requests).isEmpty();
	}

	// The provider's message is written for us and may name the key or the account; the user gets our own text.
	@Test
	void aRefusedKeyOrRequestIsABadGatewayThatNeverRepeatsTheProvidersMessage() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				new Reply(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-abc***.\"}}"),
				new Reply(422, "{\"detail\":\"Model not found.\"}"));
		AiProviderClient client = openAi(server);

		assertBadGateway(() -> client.embeddings(List.of("Java")));
		assertBadGateway(() -> client.embeddings(List.of("Java")));
	}

	@Test
	void aProviderOutageIsABadGatewayNotABadRequest() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(), new Reply(500, "{\"error\":\"overloaded\"}"),
				new Reply(503, ""));
		AiProviderClient client = openAi(server);

		assertBadGateway(() -> client.embeddings(List.of("Java")));
		assertBadGateway(() -> client.embeddings(List.of("Java")));
	}

	// A gateway in front of the provider answers with HTML; reading it as JSON used to end in a 500.
	@Test
	void aGatewayErrorPageThatIsNotJsonIsABadGatewayToo() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				new Reply(502, "<html><body><h1>502 Bad Gateway</h1></body></html>"),
				new Reply(200, "<html>maintenance</html>"));
		AiProviderClient client = openAi(server);

		assertBadGateway(() -> client.embeddings(List.of("Java")));
		assertBadGateway(() -> client.structuredProfile("mentee", "CV metni", ANY_SCHEMA));
	}

	@Test
	void openAiIsCalledWithItsOwnKeyAndModelAndWithoutTheOpenRouterTitle() throws Exception {
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> title = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		HttpServer server = startServer(exchange -> {
			path.set(exchange.getRequestURI().getPath());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			title.set(exchange.getRequestHeaders().getFirst("X-Title"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			writeJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"Ahmet\\\"}\"}}]}");
		});
		providers.add(server);
		// A base URL copied with its trailing slash must not turn into //chat/completions.
		AiProviderClient client = openAi(baseUrl(server) + "/");

		JsonNode profile = client.structuredProfile("mentee", "CV metni", ANY_SCHEMA);

		assertThat(profile.path("name").asString()).isEqualTo("Ahmet");
		assertThat(path.get()).isEqualTo("/chat/completions");
		assertThat(authorization.get()).isEqualTo("Bearer openai-key");
		assertThat(title.get()).isNull();
		JsonNode request = jsonUtils.readTree(body.get());
		assertThat(request.path("model").asString()).isEqualTo("gpt-4o-mini");
		assertThat(request.path("response_format").path("json_schema").path("name").asString())
				.isEqualTo("mentee_profile");
	}

	@Test
	void theProviderIsOpenAiUnlessOpenRouterIsNamed() throws Exception {
		assertThat(client(new AiProperties(null, null, null)).providerName()).isEqualTo("openai");
		assertThat(client(new AiProperties(null, null, null)).embeddingModel()).isEqualTo("text-embedding-3-small");
		AiProviderClient openRouter = client(new AiProperties(AiProvider.OPENROUTER, null, null));
		assertThat(openRouter.providerName()).isEqualTo("openrouter");
		assertThat(openRouter.embeddingModel()).isEqualTo("openai/text-embedding-3-small");
	}

	@Test
	void reportsUseTheReportModelAndFallBackToTheProfileModelWhenNoneIsSet() throws Exception {
		assertThat(client(openRouterReportModel("google/gemini-2.5-flash")).resolveReportModel())
				.isEqualTo("google/gemini-2.5-flash");
		assertThat(client(openRouterReportModel(" ")).resolveReportModel()).isEqualTo("openai/gpt-4o-mini");
		assertThat(client(openRouterReportModel(null)).resolveReportModel()).isEqualTo("openai/gpt-4o-mini");

		AiProviderClient openAi = client(new AiProperties(AiProvider.OPENAI, null,
				new AiProperties.OpenRouter(null, null, null, "google/gemini-2.5-flash", null, null)));
		assertThat(openAi.resolveReportModel()).isEqualTo("gpt-4o-mini");
	}

	private static AiProperties openRouterReportModel(String reportModel) {
		return new AiProperties(AiProvider.OPENROUTER, null,
				new AiProperties.OpenRouter(null, null, "openai/gpt-4o-mini", reportModel, null, null));
	}

	@Test
	void embeddingsOfNothingAskTheProviderNothing() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, new Reply(500, "{}"));

		assertThat(openAi(server).embeddings(List.of())).isEmpty();
		assertThat(requests).isEmpty();
	}

	@Test
	void embeddingsAreRefusedWhenTheProviderDoesNotReturnOneVectorPerText() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				new Reply(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}"), new Reply(200, "{\"object\":\"list\"}"));
		AiProviderClient client = openAi(server);

		assertThatThrownBy(() -> client.embeddings(List.of("Java", "Spring")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("invalid embedding count");
		assertThatThrownBy(() -> client.embeddings(List.of("Java")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("invalid embedding count");
	}

	@Test
	void anEmptyEmbeddingVectorIsRefused() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				new Reply(200, "{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[]}]}"));
		AiProviderClient client = openAi(server);

		assertThatThrownBy(() -> client.embeddings(List.of("Java", "Spring")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("empty embedding");
	}

	@Test
	void aReportFromAnOpenAiModelIsAskedForInTheStrictSchemaWithoutTouchingReasoning() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{\"title\":\"Rapor\"}"));
		JsonNode schema = jsonUtils.readTree("{\"type\":\"object\",\"required\":[\"summary\"]}");

		JsonNode report = openRouter(server, "openai/gpt-4o-mini").generateMeetingReport("[00:10] Ayşe: Merhaba",
				schema);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
		assertThat(requests).hasSize(1);
		JsonNode request = requests.get(0);
		assertThat(request.path("model").asString()).isEqualTo("openai/gpt-4o-mini");
		assertThat(request.path("response_format").path("json_schema").path("name").asString())
				.isEqualTo("meeting_report");
		assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
		assertThat(request.path("response_format").path("json_schema").path("schema")).isEqualTo(schema);
		assertThat(request.has("reasoning")).isFalse();
		assertThat(systemPrompt(request)).contains("meeting reports").endsWith(jsonUtils.writeValue(schema));
		assertThat(userText(request)).isEqualTo("[00:10] Ayşe: Merhaba");
	}

	@Test
	void aModelThatRefusesTheStrictSchemaIsAskedAgainForAnyJsonObject() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, refusal("json_schema is not supported by this model"),
				answer("{\"title\":\"Rapor\"}"));

		JsonNode report = openRouter(server, "openai/o4-mini").generateMeetingReport("Toplantı metni", ANY_SCHEMA);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
		assertThat(requests).extracting(AiProviderClientTest::responseFormat)
				.containsExactly("json_schema", "json_object");
	}

	@Test
	void aStrictAnswerThatCannotBeReadIsAskedForAgain() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("Rapor hazır değil."), answer("{\"title\":\"Rapor\"}"));

		JsonNode report = openRouter(server, "openai/gpt-4o-mini").generateMeetingReport("Toplantı metni",
				ANY_SCHEMA);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
		assertThat(requests).extracting(AiProviderClientTest::responseFormat)
				.containsExactly("json_schema", "json_object");
	}

	@Test
	void aModelThatRefusesEveryResponseFormatIsAskedWithoutOne() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, refusal("response_format is not supported"),
				answer("{\"title\":\"Rapor\"}"));

		JsonNode report = openAi(server).generateMeetingReport("Toplantı metni", ANY_SCHEMA);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
		assertThat(requests).extracting(AiProviderClientTest::responseFormat).containsExactly("json_object", "none");
		assertThat(requests).extracting(request -> request.path("model").asString()).containsOnly("gpt-4o-mini");
		assertThat(userText(requests.get(1))).isEqualTo("Toplantı metni");
	}

	@Test
	void aReportIsABadGatewayOnceEveryFormatWasRefused() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, refusal("The model is overloaded."));
		AiProviderClient client = openRouter(server, "openai/gpt-4o-mini");

		assertBadGateway(() -> client.generateMeetingReport("Toplantı metni", ANY_SCHEMA));
		assertThat(requests).extracting(AiProviderClientTest::responseFormat)
				.containsExactly("json_schema", "json_object", "none");
	}

	@Test
	void aGoogleModelKeepsReasoningOffThroughEveryFormatWhenReasoningIsNotTheProblem() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, refusal("Provider returned error"));
		AiProviderClient client = openRouter(server, "google/gemini-2.5-flash");

		assertBadGateway(() -> client.generateMeetingReport("Toplantı metni", ANY_SCHEMA));
		assertThat(requests).extracting(AiProviderClientTest::responseFormat)
				.containsExactly("json_schema", "json_object", "none");
		assertThat(requests).extracting(request -> request.path("reasoning").path("effort").asString())
				.containsExactly("none", "none", "none");
	}

	@Test
	void aReportAnswerSplitIntoContentPartsIsJoined() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(), new Reply(200, """
				{"choices": [{"message": {"content": [
				  {"type": "text", "text": "{\\"title\\":"},
				  {"type": "image_url", "image_url": {"url": "https://example.test/x.png"}},
				  "\\"Rapor\\"",
				  {"type": "text", "text": "}"}
				]}}]}
				"""));

		JsonNode report = openAi(server).generateMeetingReport("Toplantı metni", ANY_SCHEMA);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
	}

	@Test
	void aReportWrappedInAMarkdownFenceIsStillRead() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				answer("```json\n{\"title\":\"Rapor\",\"summary\":\"Özet\"}\n```"));

		JsonNode report = openAi(server).generateMeetingReport("Toplantı metni", ANY_SCHEMA);

		assertThat(report.path("title").asString()).isEqualTo("Rapor");
		assertThat(report.path("summary").asString()).isEqualTo("Özet");
	}

	@Test
	void aReportAnswerWithoutAnyTextIsRefused() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, new Reply(200, "{\"choices\":[{\"message\":{\"content\":null}}]}"));
		AiProviderClient client = openAi(server);

		assertThatThrownBy(() -> client.generateMeetingReport("Toplantı metni", ANY_SCHEMA))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The AI provider returned an empty meeting report.");
		assertThat(requests).hasSize(1);
	}

	@Test
	void aReportThatIsNotJsonIsRefused() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(), answer("Toplantıda karar alınmadı."));
		AiProviderClient client = openAi(server);

		assertThatThrownBy(() -> client.generateMeetingReport("Toplantı metni", ANY_SCHEMA))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("The AI provider returned an invalid meeting report.");
	}

	@Test
	void transcriptCleanupSendsTheLinesToTheReportModelWithTheSchemaItMustFollow() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{\"segments\":[{\"id\":\"s1\",\"text\":\"Merhaba.\"}]}"));
		JsonNode schema = jsonUtils.readTree("{\"type\":\"object\",\"required\":[\"segments\"]}");

		JsonNode cleaned = openRouter(server, "anthropic/claude-haiku").cleanTranscript("s1\tmeraba", schema);

		assertThat(cleaned.path("segments").path(0).path("text").asString()).isEqualTo("Merhaba.");
		assertThat(requests).hasSize(1);
		JsonNode request = requests.get(0);
		assertThat(request.path("model").asString()).isEqualTo("anthropic/claude-haiku");
		assertThat(responseFormat(request)).isEqualTo("json_object");
		assertThat(request.has("reasoning")).isFalse();
		assertThat(systemPrompt(request)).contains("speech-to-text").endsWith(jsonUtils.writeValue(schema));
		assertThat(userText(request)).isEqualTo("s1\tmeraba");
	}

	@Test
	void taskHandoverSendsTheTeamAndTasksToTheReportModelWithTheSchemaItMustFollow() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, answer("{\"assignments\":[{\"task\":\"T1\",\"member\":\"M2\"}]}"));
		JsonNode schema = jsonUtils.readTree("{\"type\":\"object\",\"required\":[\"assignments\"]}");

		JsonNode picked = openRouter(server, "anthropic/claude-haiku").taskHandover("M1\tCEO\t0\tyes", schema);

		assertThat(picked.path("assignments").path(0).path("member").asString()).isEqualTo("M2");
		assertThat(requests).hasSize(1);
		JsonNode request = requests.get(0);
		assertThat(request.path("model").asString()).isEqualTo("anthropic/claude-haiku");
		assertThat(systemPrompt(request)).contains("hand over the open tasks").endsWith(jsonUtils.writeValue(schema));
		assertThat(userText(request)).isEqualTo("M1\tCEO\t0\tyes");
	}

	@Test
	void aProviderThatDropsTheConnectionIsReportedAsUnreachable() throws Exception {
		HttpServer server = startServer(HttpExchange::close);
		providers.add(server);
		AiProviderClient client = openAi(server);

		assertBadGateway(() -> client.embeddings(List.of("Java")));
	}

	// The fallback to a looser format exists for a model that refuses the strict schema, not for an outage: asking
	// again would only fail again, slower.
	@Test
	void aReportIsNotAskedForAgainWhenTheProviderIsDown() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, new Reply(503, "{\"error\":{\"message\":\"upstream down\"}}"),
				answer("{\"title\":\"Rapor\"}"));

		assertBadGateway(() -> openRouter(server, "openai/gpt-4o-mini").generateMeetingReport("Toplantı metni",
				ANY_SCHEMA));
		assertThat(requests).hasSize(1);
	}

	@Test
	void aReportIsNotAskedForAgainWhenTheKeyIsRefused() throws Exception {
		List<JsonNode> requests = new CopyOnWriteArrayList<>();
		HttpServer server = provider(requests, new Reply(401, "{\"error\":{\"message\":\"No auth credentials\"}}"),
				answer("{\"title\":\"Rapor\"}"));

		assertBadGateway(() -> openRouter(server, "google/gemini-2.5-flash").cleanTranscript("s1\tmerhaba",
				ANY_SCHEMA));
		assertThat(requests).hasSize(1);
	}

	@Test
	void aReportIsNotAskedForAgainWhenTheConnectionDrops() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		HttpServer server = startServer(exchange -> {
			attempts.incrementAndGet();
			exchange.close();
		});
		providers.add(server);

		assertBadGateway(() -> openRouter(server, "openai/gpt-4o-mini").taskHandover("M1\tCEO", ANY_SCHEMA));
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void anInterruptedRequestIsReportedAndLeavesTheThreadInterrupted() throws Exception {
		HttpServer server = provider(new CopyOnWriteArrayList<>(),
				new Reply(200, "{\"data\":[{\"embedding\":[0.1]}]}"));
		AiProviderClient client = openAi(server);

		Thread.currentThread().interrupt();
		boolean stillInterrupted;
		try {
			assertBadGateway(() -> client.embeddings(List.of("Java")));
		} finally {
			// Reading the flag also clears it, so the interrupt never reaches the next test.
			stillInterrupted = Thread.interrupted();
		}
		assertThat(stillInterrupted).as("the interrupt stays set for whoever runs the request").isTrue();
	}

	private record Reply(int status, String body) {
	}

	/** Answers each request with the next reply (the last one repeats) and keeps what was sent. */
	private HttpServer provider(List<JsonNode> requests, Reply... replies) throws IOException {
		AtomicInteger answered = new AtomicInteger();
		HttpServer server = startServer(exchange -> {
			requests.add(jsonUtils.readTree(
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Reply reply = replies[Math.min(answered.getAndIncrement(), replies.length - 1)];
			writeJson(exchange, reply.status(), reply.body());
		});
		providers.add(server);
		return server;
	}

	private Reply answer(String content) {
		return new Reply(200,
				jsonUtils.writeValue(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
	}

	private Reply refusal(String message) {
		return new Reply(400, jsonUtils.writeValue(Map.of("error", Map.of("message", message))));
	}

	private AiProviderClient client(AiProperties properties) {
		return new AiProviderClient(jsonUtils, TestAiFeatures.allOn(), properties);
	}

	private AiProviderClient openRouter(HttpServer server, String reportModel) {
		return client(openRouterSettings(server, "test-key", reportModel));
	}

	private static AiProperties openRouterSettings(HttpServer server, String apiKey, String reportModel) {
		return new AiProperties(AiProvider.OPENROUTER, null, new AiProperties.OpenRouter(apiKey, baseUrl(server),
				"openrouter-profile", reportModel, "openrouter-embedding", "TeamTNT Spring Backend"));
	}

	private AiProviderClient openAi(HttpServer server) {
		return openAi(baseUrl(server));
	}

	private AiProviderClient openAi(String baseUrl) {
		return client(new AiProperties(AiProvider.OPENAI,
				new AiProperties.OpenAi("openai-key", baseUrl, "gpt-4o-mini", "text-embedding-3-small"), null));
	}

	private static String baseUrl(HttpServer server) {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private static void assertBadGateway(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
			assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(error.getReason())
					.isEqualTo("Yapay zekâ servisi şu anda yanıt vermiyor. Biraz sonra tekrar dene.");
		});
	}

	private static String systemPrompt(JsonNode request) {
		return request.path("messages").path(0).path("content").asString();
	}

	private static String userText(JsonNode request) {
		return request.path("messages").path(1).path("content").asString();
	}

	private static String responseFormat(JsonNode request) {
		return request.path("response_format").path("type").asString("none");
	}

	private static HttpServer startServer(HttpHandler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/chat/completions", handler);
		server.createContext("/embeddings", handler);
		server.start();
		return server;
	}

	private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}
}
