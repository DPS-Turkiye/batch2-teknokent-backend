package com.dps.teknokent.util;

import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable tokens for links, states and one-time codes. */
public final class RandomTokens {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

	private RandomTokens() {
	}

	/** {@code bytes} random bytes as unpadded base64url, safe in a URL or a header. */
	public static String urlSafe(int bytes) {
		byte[] random = new byte[bytes];
		RANDOM.nextBytes(random);
		return URL_SAFE.encodeToString(random);
	}
}
