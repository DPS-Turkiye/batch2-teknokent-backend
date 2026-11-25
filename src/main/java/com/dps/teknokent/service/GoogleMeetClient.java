package com.dps.teknokent.service;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Google's Meet REST API: one call that makes a meeting and answers its link. */
public class GoogleMeetClient {

	private final RestClient client;
	private final JsonMapper jsonMapper;
	private final Supplier<String> accessToken;
	private final String accessType;

	public GoogleMeetClient(RestClient client, JsonMapper jsonMapper, Supplier<String> accessToken,
			String accessType) {
		this.client = client;
		this.jsonMapper = jsonMapper;
		this.accessToken = accessToken;
		this.accessType = accessType;
	}

	/** Makes a meeting and answers its link; throws when Google refuses or answers without one. */
	public String createMeeting() {
		byte[] body = jsonMapper.writeValueAsBytes(Map.of("config", Map.of("accessType", accessType)));
		JsonNode space = client.post()
				.uri("/v2/spaces")
				.headers(headers -> headers.setBearerAuth(accessToken.get()))
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(JsonNode.class);
		String link = space == null ? "" : space.path("meetingUri").asString("");
		if (!link.startsWith("https://meet.google.com/")) {
			throw new IllegalStateException("Google Meet answered without a meeting link");
		}
		return link;
	}
}
