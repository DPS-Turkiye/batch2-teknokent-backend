package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.LtiProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class LtiLaunchVerifierTest {

    private static final String ISSUER = "http://localhost:8081";
    private static final String CLIENT_ID = "client-1";
    private static final String NONCE = "nonce-1";
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String CLAIM = "https://purl.imsglobal.org/spec/lti/claim/";

    private static RSAKey moodleKey;
    private static RSAKey otherKey;

    private final LtiProperties properties = new LtiProperties(ISSUER, CLIENT_ID, "1, 2",
            ISSUER + "/mod/lti/auth.php", ISSUER + "/mod/lti/certs.php", "http://localhost:3000/lti/launch", null);

    @BeforeAll
    static void keys() throws Exception {
        moodleKey = new RSAKeyGenerator(2048).keyID("moodle").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("moodle").generate();
    }

    private LtiLaunchVerifier verifier() {
        return new LtiLaunchVerifier(properties,
                LtiPlatformKeys.of(new ImmutableJWKSet<>(new JWKSet(moodleKey.toPublicJWK()))),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("42")
                .expirationTime(Date.from(NOW.plusSeconds(60)))
                .issueTime(Date.from(NOW.minusSeconds(5)))
                .claim("nonce", NONCE)
                .claim("name", "Ayşe Yılmaz")
                .claim("email", "ayse@metu.edu.tr")
                .claim(CLAIM + "deployment_id", "2")
                .claim(CLAIM + "message_type", "LtiResourceLinkRequest")
                .claim(CLAIM + "version", "1.3.0")
                .claim(CLAIM + "context", Map.of("id", "7", "label", "CENG 101", "title", "Introduction to Computing"))
                .claim(CLAIM + "roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner"));
    }

    private static String sign(JWTClaimsSet claims, RSAKey key, JWSAlgorithm algorithm) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        JWTClaimsSet.Builder builder = validClaims();
        change.accept(builder);
        return sign(builder.build(), moodleKey, JWSAlgorithm.RS256);
    }

    @Test
    void acceptsAValidLaunchAndKeepsWhoCameFromWhichCourse() throws Exception {
        LtiLaunchClaims claims = verifier().verify(token(builder -> {
        }), NONCE);

        assertThat(claims.issuer()).isEqualTo(ISSUER);
        assertThat(claims.subject()).isEqualTo("42");
        assertThat(claims.fullName()).isEqualTo("Ayşe Yılmaz");
        assertThat(claims.email()).isEqualTo("ayse@metu.edu.tr");
        assertThat(claims.contextId()).isEqualTo("7");
        assertThat(claims.contextLabel()).isEqualTo("CENG 101");
        assertThat(claims.contextTitle()).isEqualTo("Introduction to Computing");
        assertThat(claims.roles()).containsExactly("http://purl.imsglobal.org/vocab/lis/v2/membership#Learner");
    }

    @Test
    void buildsTheNameFromGivenAndFamilyNamesWhenThereIsNoFullName() throws Exception {
        LtiLaunchClaims claims = verifier().verify(token(builder -> builder
                .claim("name", null).claim("given_name", "Ali").claim("family_name", "Kaya")), NONCE);

        assertThat(claims.fullName()).isEqualTo("Ali Kaya");
    }

    @Test
    void refusesATokenSignedWithAnotherKey() throws Exception {
        String forged = sign(validClaims().build(), otherKey, JWSAlgorithm.RS256);

        assertUnauthorized(() -> verifier().verify(forged, NONCE));
    }

    @Test
    void refusesAnyAlgorithmButRs256() throws Exception {
        String rs512 = sign(validClaims().build(), moodleKey, JWSAlgorithm.RS512);

        assertUnauthorized(() -> verifier().verify(rs512, NONCE));
    }

    @Test
    void refusesAWrongNonce() throws Exception {
        String token = token(builder -> {
        });

        assertUnauthorized(() -> verifier().verify(token, "another-nonce"));
    }

    @Test
    void refusesAnotherIssuer() throws Exception {
        String token = token(builder -> builder.issuer("https://evil.example"));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void refusesATokenForAnotherTool() throws Exception {
        String token = token(builder -> builder.audience("someone-else"));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void asksForTheAuthorizedPartyWhenThereAreSeveralAudiences() throws Exception {
        String withoutAzp = token(builder -> builder.audience(List.of(CLIENT_ID, "other")));
        String withAzp = token(builder -> builder.audience(List.of(CLIENT_ID, "other")).claim("azp", CLIENT_ID));

        assertUnauthorized(() -> verifier().verify(withoutAzp, NONCE));
        assertThat(verifier().verify(withAzp, NONCE).subject()).isEqualTo("42");
    }

    @Test
    void refusesAnExpiredToken() throws Exception {
        String token = token(builder -> builder.expirationTime(Date.from(NOW.minusSeconds(120))));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void refusesATokenIssuedInTheFuture() throws Exception {
        String token = token(builder -> builder.issueTime(Date.from(NOW.plusSeconds(300))));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void refusesAnUnknownDeployment() throws Exception {
        String token = token(builder -> builder.claim(CLAIM + "deployment_id", "9"));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void refusesOtherMessageTypesAndVersions() throws Exception {
        String deepLink = token(builder -> builder.claim(CLAIM + "message_type", "LtiDeepLinkingRequest"));
        String lti11 = token(builder -> builder.claim(CLAIM + "version", "1.1"));

        assertUnauthorized(() -> verifier().verify(deepLink, NONCE));
        assertUnauthorized(() -> verifier().verify(lti11, NONCE));
    }

    @Test
    void refusesALaunchFromOutsideACourse() throws Exception {
        String token = token(builder -> builder.claim(CLAIM + "context", null));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void acceptsACourseIdLabelAndTitleSentAsNumbers() throws Exception {
        LtiLaunchClaims claims = verifier().verify(token(builder -> builder.claim(CLAIM + "context",
                Map.of("id", 7, "label", 101, "title", 2026))), NONCE);

        assertThat(claims.contextId()).isEqualTo("7");
        assertThat(claims.contextLabel()).isEqualTo("101");
        assertThat(claims.contextTitle()).isEqualTo("2026");
    }

    @Test
    void refusesACourseIdThatIsNeitherTextNorANumber() throws Exception {
        String token = token(builder -> builder.claim(CLAIM + "context", Map.of("id", List.of("7"))));

        assertUnauthorized(() -> verifier().verify(token, NONCE));
    }

    @Test
    void refusesGarbage() {
        assertUnauthorized(() -> verifier().verify("not-a-jwt", NONCE));
    }

    @Test
    void saysLtiIsUnavailableWhenNotConfigured() {
        LtiLaunchVerifier unconfigured = new LtiLaunchVerifier(
                new LtiProperties(null, null, null, null, null, null, null), LtiPlatformKeys.unavailable());

        assertThatThrownBy(() -> unconfigured.verify("x", NONCE))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
