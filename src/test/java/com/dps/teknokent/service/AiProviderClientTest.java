package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class AiProviderClientTest {

    private final JsonUtils jsonUtils = new JsonUtils();

    @Test
    void providerNameAndModelSelection_useOpenrouterSettings() throws Exception {
        AiProviderClient client = new AiProviderClient(jsonUtils);

        setField(client, "provider", "openrouter");
        setField(client, "openRouterEmbeddingModel", "openrouter-embedding");
        setField(client, "openRouterProfileModel", "openrouter-profile");

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
            AiProviderClient client = new AiProviderClient(jsonUtils);
            setField(client, "provider", "openrouter");
            setField(client, "openRouterApiKey", "test-key");
            setField(client, "openRouterBaseUrl", "http://localhost:" + server.getAddress().getPort());
            setField(client, "openRouterProfileModel", "openrouter-profile");
            setField(client, "openRouterAppName", "TeamTNT Spring Backend");

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

            assertThat(result.get("name").asText()).isEqualTo("Ahmet");
            assertThat(result.get("skills").get(0).asText()).isEqualTo("Java");
            assertThat(result.get("skills").get(1).asText()).isEqualTo("Spring");
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
            AiProviderClient client = new AiProviderClient(jsonUtils);
            setField(client, "provider", "openrouter");
            setField(client, "openRouterApiKey", "test-key");
            setField(client, "openRouterBaseUrl", "http://localhost:" + server.getAddress().getPort());
            setField(client, "openRouterEmbeddingModel", "openrouter-embedding");
            setField(client, "openRouterAppName", "TeamTNT Spring Backend");

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
            AiProviderClient client = new AiProviderClient(jsonUtils);
            setField(client, "provider", "openai");
            setField(client, "openAiApiKey", "test-key");
            setField(client, "openAiBaseUrl", "http://localhost:" + server.getAddress().getPort());
            setField(client, "openAiProfileModel", "gpt-4o-mini");

            JsonNode schema = jsonUtils.readTree("""
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
            AiProviderClient client = new AiProviderClient(jsonUtils);
            setField(client, "provider", "openrouter");
            setField(client, "openRouterApiKey", "test-key");
            setField(client, "openRouterBaseUrl", "http://localhost:" + server.getAddress().getPort());
            setField(client, "openRouterReportModel", "google/gemini-3.8-flash");
            setField(client, "openRouterAppName", "TeamTNT Spring Backend");

            JsonNode schema = jsonUtils.readTree("{\"type\":\"object\"}");
            JsonNode report = client.generateMeetingReport("Toplanti metni", schema, null);

            assertThat(report.path("title").asText()).isEqualTo("Rapor");
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).contains("\"reasoning\"");
            assertThat(bodies.get(1)).doesNotContain("\"reasoning\"");
        } finally {
            server.stop(0);
        }
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

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = AiProviderClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
