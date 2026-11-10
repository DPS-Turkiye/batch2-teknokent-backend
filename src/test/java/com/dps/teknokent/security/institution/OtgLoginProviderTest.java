package com.dps.teknokent.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.dps.teknokent.exception.InvalidRequestException;

import tools.jackson.databind.json.JsonMapper;

class OtgLoginProviderTest {

	private static final String ISSUER = "https://otg.example/auth/v1";
	private static final String USERINFO = "https://otg.example/api/oauth/userinfo";
	private static final String REDIRECT = "http://localhost:3000/auth/otg/callback";

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);
	private MockRestServiceServer otg;
	private OtgLoginProvider provider;

	@BeforeEach
	void setUp() {
		when(redis.opsForValue()).thenReturn(values);
		RestClient.Builder builder = RestClient.builder();
		otg = MockRestServiceServer.bindTo(builder).build();
		provider = new OtgLoginProvider(redis, JsonMapper.builder().build(), builder.build(), ISSUER + "/", USERINFO,
				"client-1", "secret-1", REDIRECT);
	}

	@Test
	void isAnIdentityOnlyLoginNamedOtg() {
		assertThat(provider.id()).isEqualTo("otg");
		assertThat(provider.grantsAccess()).isFalse();
	}

	// scope=email, never openid (OTG has no id_token); PKCE S256 with the verifier kept server-side under the state.
	@Test
	void startSendsTheBrowserToOtgWithPkceAndKeepsTheVerifier() {
		String redirect = provider.startLogin("state-1");

		ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
		verify(values).set(eq(OtgLoginProvider.VERIFIER_KEY + "state-1"), verifier.capture(),
				eq(OtgLoginProvider.VERIFIER_TTL));
		assertThat(verifier.getValue()).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
		UriComponents url = UriComponentsBuilder.fromUriString(redirect).build();
		assertThat(url.getScheme() + "://" + url.getHost() + url.getPath()).isEqualTo(ISSUER + "/oauth/authorize");
		Map<String, String> query = url.getQueryParams().toSingleValueMap();
		assertThat(query).containsEntry("response_type", "code")
				.containsEntry("client_id", "client-1")
				.containsEntry("scope", "email")
				.containsEntry("state", "state-1")
				.containsEntry("code_challenge", OtgLoginProvider.challenge(verifier.getValue()))
				.containsEntry("code_challenge_method", "S256")
				.doesNotContainKey("client_secret");
		assertThat(UriComponentsBuilder.fromUriString(redirect).build(true).getQueryParams().getFirst("redirect_uri"))
				.isEqualTo("http%3A%2F%2Flocalhost%3A3000%2Fauth%2Fotg%2Fcallback");
	}

	// RFC 7636 appendix B.
	@Test
	void theChallengeIsTheBase64UrlSha256OfTheVerifier() {
		assertThat(OtgLoginProvider.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
				.isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
	}

	@Test
	void theCallbackTradesTheCodeAndReadsWhoSignedIn() {
		when(values.getAndDelete(OtgLoginProvider.VERIFIER_KEY + "state-1")).thenReturn("verifier-1");
		String basic = Base64.getEncoder().encodeToString("client-1:secret-1".getBytes(StandardCharsets.UTF_8));
		otg.expect(requestTo(ISSUER + "/oauth/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Basic " + basic))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
				.andExpect(content().formDataContains(Map.of(
						"grant_type", "authorization_code",
						"code", "code-1",
						"redirect_uri", REDIRECT,
						"code_verifier", "verifier-1")))
				.andRespond(withSuccess("""
						{"access_token":"access-1","token_type":"Bearer","expires_in":3600,"refresh_token":"r"}
						""", MediaType.APPLICATION_JSON));
		otg.expect(requestTo(USERINFO))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer access-1"))
				.andRespond(withSuccess("""
						{"sub":"a72ab40d-b563","email":"ogrenci@ornek.com","email_verified":true,"name":"Ayşe Yılmaz"}
						""", MediaType.APPLICATION_JSON));

		InstitutionIdentity identity = provider.completeLogin("state-1", Map.of("code", "code-1", "state", "state-1"));

		otg.verify();
		assertThat(identity.persistentId()).isEqualTo("a72ab40d-b563");
		assertThat(identity.email()).isEqualTo("ogrenci@ornek.com");
		assertThat(identity.emailVerified()).isTrue();
		assertThat(identity.fullName()).isEqualTo("Ayşe Yılmaz");
		assertThat(identity.kinds()).isEmpty();
	}

	@Test
	void aMissingNameOrVerificationIsNotAVerifiedAddress() {
		when(values.getAndDelete(anyString())).thenReturn("verifier-1");
		otg.expect(requestTo(ISSUER + "/oauth/token"))
				.andRespond(withSuccess("{\"access_token\":\"access-1\"}", MediaType.APPLICATION_JSON));
		otg.expect(requestTo(USERINFO))
				.andRespond(withSuccess("{\"sub\":\"s1\",\"email\":\"x@ornek.com\",\"name\":null}",
						MediaType.APPLICATION_JSON));

		InstitutionIdentity identity = provider.completeLogin("state-1", Map.of("code", "code-1"));

		assertThat(identity.emailVerified()).isFalse();
		assertThat(identity.fullName()).isNull();
	}

	// "Vazgeç" on OTG's consent screen comes back as error=access_denied; the verifier is spent either way.
	@Test
	void aCancelledConsentIsAPoliteRefusal() {
		when(values.getAndDelete(OtgLoginProvider.VERIFIER_KEY + "state-1")).thenReturn("verifier-1");

		assertThatThrownBy(() -> provider.completeLogin("state-1", Map.of("error", "access_denied")))
				.isInstanceOf(InvalidRequestException.class);
		verify(values).getAndDelete(OtgLoginProvider.VERIFIER_KEY + "state-1");
		otg.verify();
	}

	@Test
	void aCallbackWithoutItsVerifierOrCodeIsRefused() {
		when(values.getAndDelete(anyString())).thenReturn(null);
		assertThatThrownBy(() -> provider.completeLogin("state-1", Map.of("code", "code-1")))
				.isInstanceOf(InvalidRequestException.class);

		when(values.getAndDelete(anyString())).thenReturn("verifier-1");
		assertThatThrownBy(() -> provider.completeLogin("state-1", Map.of()))
				.isInstanceOf(InvalidRequestException.class);
		otg.verify();
	}

	@Test
	void aRefusedTokenCallIsABadGateway() {
		when(values.getAndDelete(anyString())).thenReturn("verifier-1");
		otg.expect(requestTo(ISSUER + "/oauth/token"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}"));

		assertThatThrownBy(() -> provider.completeLogin("state-1", Map.of("code", "used")))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void userinfoWithoutASubIsABadGateway() {
		when(values.getAndDelete(anyString())).thenReturn("verifier-1");
		otg.expect(requestTo(ISSUER + "/oauth/token"))
				.andRespond(withSuccess("{\"access_token\":\"access-1\"}", MediaType.APPLICATION_JSON));
		otg.expect(requestTo(USERINFO))
				.andRespond(withSuccess("{\"email\":\"x@ornek.com\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.completeLogin("state-1", Map.of("code", "code-1")))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void refusesToStartWithoutItsSettingsOrOverPlainHttp() {
		JsonMapper json = JsonMapper.builder().build();
		RestClient client = RestClient.create();
		assertThatThrownBy(() -> new OtgLoginProvider(redis, json, client, ISSUER, USERINFO, "client-1", " ", REDIRECT))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("OTG_CLIENT_SECRET");
		assertThatThrownBy(() -> new OtgLoginProvider(redis, json, client, "http://otg.example/auth/v1", USERINFO,
				"client-1", "secret-1", REDIRECT))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
		assertThatThrownBy(() -> new OtgLoginProvider(redis, json, client, ISSUER, USERINFO, "client-1", "secret-1", ""))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("OTG_REDIRECT_URI");
		verify(values, org.mockito.Mockito.never()).set(anyString(), anyString(), any(java.time.Duration.class));
	}
}
