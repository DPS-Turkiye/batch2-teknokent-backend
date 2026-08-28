package com.dps.teknokent.util;

import java.security.SecureRandom;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

@Component
public class InviteCodeGenerator {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int LENGTH = 8;
	private static final int MAX_ATTEMPTS = 16;

	private final SecureRandom random = new SecureRandom();

	public String nextCode() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}

	public String nextUnique(Predicate<String> taken) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			String code = nextCode();
			if (!taken.test(code)) {
				return code;
			}
		}
		throw new IllegalStateException("Could not generate a unique invite code.");
	}
}
