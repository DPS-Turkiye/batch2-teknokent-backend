package com.dps.teknokent.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.LtiProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * The tool's own RSA key. Moodle asks for the tool's public key set at registration; a basic launch never uses
 * it, later LTI services (names and roles, deep linking) will. Without a configured key a throwaway one is made
 * at start-up, which is fine locally but changes on every restart.
 */
@Component
public class LtiToolKeys {

	private static final Logger log = LoggerFactory.getLogger(LtiToolKeys.class);

	private final RSAKey key;

	public LtiToolKeys(LtiProperties properties) {
		this.key = properties.toolPrivateKey() == null || properties.toolPrivateKey().isBlank()
				? generated()
				: fromPem(properties.toolPrivateKey());
	}

	/** The public half only, as Moodle reads it from the key set URL. */
	public Map<String, Object> publicKeySet() {
		return new JWKSet(key.toPublicJWK()).toJSONObject();
	}

	private static RSAKey generated() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair pair = generator.generateKeyPair();
			log.info("LTI_TOOL_PRIVATE_KEY is empty; using a throwaway tool key until restart.");
			return rsaKey((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("RSA is not available.", exception);
		}
	}

	private static RSAKey fromPem(String pem) {
		String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
		try {
			KeyFactory factory = KeyFactory.getInstance("RSA");
			RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory
					.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
			RSAPublicKey publicKey = (RSAPublicKey) factory
					.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
			return rsaKey(publicKey, privateKey);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException | ClassCastException exception) {
			throw new IllegalStateException("LTI_TOOL_PRIVATE_KEY is not a PKCS#8 RSA private key.", exception);
		}
	}

	private static RSAKey rsaKey(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
		RSAKey withoutId = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
		try {
			return new RSAKey.Builder(withoutId)
					.keyUse(KeyUse.SIGNATURE)
					.algorithm(JWSAlgorithm.RS256)
					.keyID(withoutId.computeThumbprint().toString())
					.build();
		} catch (com.nimbusds.jose.JOSEException exception) {
			throw new IllegalStateException("Could not name the LTI tool key.", exception);
		}
	}
}
