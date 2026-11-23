package com.dps.teknokent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of a text as 64 hex characters. */
public final class Sha256 {

	private Sha256() {
	}

	public static String hex(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 is part of every Java platform.", missing);
		}
	}
}
