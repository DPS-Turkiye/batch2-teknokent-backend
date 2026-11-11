package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.config.LtiProperties;

class LtiToolKeysTest {

    private static LtiProperties withKey(String pem) {
        return new LtiProperties(null, null, null, null, null, null, pem);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyKey(LtiToolKeys keys) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) keys.publicKeySet().get("keys");
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Test
    void publishesOnlyThePublicHalfOfAConfiguredKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        Map<String, Object> key = onlyKey(new LtiToolKeys(withKey(pem)));

        assertThat(key).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig")
                .containsKeys("n", "e", "kid").doesNotContainKeys("d", "p", "q");
        assertThat(onlyKey(new LtiToolKeys(withKey(pem))).get("kid")).isEqualTo(key.get("kid"));
    }

    @Test
    void makesAThrowawayKeyWhenNoneIsConfigured() {
        assertThat(onlyKey(new LtiToolKeys(withKey(""))))
                .containsEntry("kty", "RSA").doesNotContainKey("d");
    }

    @Test
    void refusesSomethingThatIsNotAPrivateKey() {
        assertThatThrownBy(() -> new LtiToolKeys(withKey("not a key")))
                .isInstanceOf(IllegalStateException.class);
    }
}
