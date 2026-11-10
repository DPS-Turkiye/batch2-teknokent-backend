package com.dps.teknokent.security.institution;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.Texts;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "OTG ile giriş yap": ODTÜ TEKNOKENT Girişimcilik's own login. Plain OAuth 2.0 authorization code with PKCE (S256)
 * and {@code client_secret_basic}; OTG issues no id_token, so who signed in comes from its userinfo endpoint, and
 * {@code openid} must never be asked for. OTG only proves who someone is ({@link #grantsAccess()} false): it never
 * creates an account or grants a role. Off unless {@code OTG_LOGIN_ENABLED=true}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.otg-login.enabled", havingValue = "true")
public class OtgLoginProvider implements InstitutionLoginProvider {

	public static final String ID = "otg";
	static final String VERIFIER_KEY = "otg-login:verifier:";
	/** As long as the state it belongs to. */
	static final Duration VERIFIER_TTL = Duration.ofMinutes(10);
	static final String SCOPE = "email";
	private static final String ACCESS_DENIED = "access_denied";
	private static final int MAX_CODE_LENGTH = 2048;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final StringRedisTemplate redis;
	private final RestClient http;
	private final JsonMapper jsonMapper;
	private final String authorizeUrl;
	private final String tokenUrl;
	private final String userinfoUrl;
	private final String clientId;
	private final String clientSecret;
	private final String redirectUri;

	@Autowired
	public OtgLoginProvider(StringRedisTemplate redis, JsonMapper jsonMapper,
			@Value("${app.otg-login.issuer}") String issuer,
			@Value("${app.otg-login.userinfo-url}") String userinfoUrl,
			@Value("${app.otg-login.client-id}") String clientId,
			@Value("${app.otg-login.client-secret}") String clientSecret,
			@Value("${app.otg-login.redirect-uri}") String redirectUri) {
		this(redis, jsonMapper, defaultClient(), issuer, userinfoUrl, clientId, clientSecret, redirectUri);
	}

	OtgLoginProvider(StringRedisTemplate redis, JsonMapper jsonMapper, RestClient http, String issuer,
			String userinfoUrl, String clientId, String clientSecret, String redirectUri) {
		this.redis = redis;
		this.jsonMapper = jsonMapper;
		this.http = http;
		String base = requireHttps("OTG_ISSUER", issuer).replaceAll("/+$", "");
		this.authorizeUrl = base + "/oauth/authorize";
		this.tokenUrl = base + "/oauth/token";
		this.userinfoUrl = requireHttps("OTG_USERINFO_URL", userinfoUrl);
		this.clientId = require("OTG_CLIENT_ID", clientId);
		this.clientSecret = require("OTG_CLIENT_SECRET", clientSecret);
		this.redirectUri = require("OTG_REDIRECT_URI", redirectUri);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean grantsAccess() {
		return false;
	}

	/** The PKCE verifier never leaves the backend: it waits in Redis under the state until the callback. */
	@Override
	public String startLogin(String state) {
		String verifier = randomUrlSafe(48);
		redis.opsForValue().set(VERIFIER_KEY + state, verifier, VERIFIER_TTL);
		// Values as variables, so each is strictly encoded (a "+" or "&" in one can never change the query).
		return UriComponentsBuilder.fromUriString(authorizeUrl)
				.queryParam("response_type", "code")
				.queryParam("client_id", "{clientId}")
				.queryParam("redirect_uri", "{redirectUri}")
				.queryParam("scope", SCOPE)
				.queryParam("state", "{state}")
				.queryParam("code_challenge", "{challenge}")
				.queryParam("code_challenge_method", "S256")
				.encode()
				.buildAndExpand(Map.of("clientId", clientId, "redirectUri", redirectUri, "state", state, "challenge",
						challenge(verifier)))
				.toUriString();
	}

	@Override
	public InstitutionIdentity completeLogin(String state, Map<String, String> callback) {
		String verifier = state == null ? null : redis.opsForValue().getAndDelete(VERIFIER_KEY + state);
		String error = callback.get("error");
		if (error != null) {
			throw new InvalidRequestException(Texts.forRequest(
					ACCESS_DENIED.equals(error) ? "error.otgLoginCancelled" : "error.otgLoginFailed"));
		}
		if (verifier == null) {
			throw new InvalidRequestException(Texts.forRequest("error.institutionLoginExpired"));
		}
		String code = callback.get("code");
		if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
			throw new InvalidRequestException(Texts.forRequest("error.otgLoginFailed"));
		}
		Userinfo person = userinfo(accessToken(code, verifier));
		if (person.sub() == null || person.sub().isBlank()) {
			throw unavailable("userinfo has no sub", null);
		}
		return new InstitutionIdentity(person.sub().trim(), person.email(), Boolean.TRUE.equals(person.emailVerified()),
				person.name(), Set.of(), null, null, null, null, null, null, false);
	}

	private String accessToken(String code, String verifier) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		form.add("code_verifier", verifier);
		TokenResponse token = read(call("token", () -> http.post()
				.uri(URI.create(tokenUrl))
				.headers(headers -> headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(form)
				.retrieve()
				.body(String.class)), TokenResponse.class);
		if (token.accessToken() == null || token.accessToken().isBlank()) {
			throw unavailable("token response has no access_token", null);
		}
		return token.accessToken();
	}

	private Userinfo userinfo(String accessToken) {
		return read(call("userinfo", () -> http.get()
				.uri(URI.create(userinfoUrl))
				.headers(headers -> headers.setBearerAuth(accessToken))
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class)), Userinfo.class);
	}

	/** Logs which call failed and its status, never the code, the tokens or the secret. */
	private static String call(String step, Supplier<String> request) {
		try {
			String body = request.get();
			if (body == null || body.isBlank()) {
				throw unavailable(step + " answered with an empty body", null);
			}
			return body;
		} catch (RestClientException exception) {
			throw unavailable(step + " failed: " + exception.getClass().getSimpleName(), exception);
		}
	}

	private <T> T read(String body, Class<T> type) {
		try {
			return jsonMapper.readValue(body, type);
		} catch (JacksonException exception) {
			throw unavailable("answer is not the expected JSON", exception);
		}
	}

	private static ResponseStatusException unavailable(String reason, Exception cause) {
		log.warn("OTG login: {}", reason);
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY, Texts.forRequest("error.otgLoginFailed"), cause);
	}

	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String randomUrlSafe(int bytes) {
		byte[] random = new byte[bytes];
		RANDOM.nextBytes(random);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
	}

	private static String require(String name, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("OTG_LOGIN_ENABLED=true needs " + name + ".");
		}
		return value.trim();
	}

	private static String requireHttps(String name, String value) {
		String url = require(name, value);
		if (!url.startsWith("https://")) {
			throw new IllegalStateException(name + " must be an https URL.");
		}
		return url;
	}

	private static RestClient defaultClient() {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		return RestClient.builder().requestFactory(requestFactory).build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TokenResponse(@JsonProperty("access_token") String accessToken) {
	}

	/** OTG's {@code /api/oauth/userinfo}; {@code name} may be null. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Userinfo(String sub, String email, @JsonProperty("email_verified") Boolean emailVerified, String name) {
	}
}
