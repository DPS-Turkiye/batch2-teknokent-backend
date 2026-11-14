package com.dps.teknokent.domain;

import java.util.regex.Pattern;

import com.dps.teknokent.exception.InvalidRequestException;

/**
 * A person's answer to a data-processing consent, with the version of the text they were shown. The version is what
 * makes a stored consent mean something: without it nobody can tell what was agreed to.
 */
public record ConsentAnswer(boolean accepted, String textVersion) {

	private static final Pattern TEXT_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	/** Refuses a missing consent, or one that does not name the text version it was given to. */
	public void requireAccepted() {
		if (!accepted) {
			throw new InvalidRequestException("Document processing consent is required.");
		}
		if (textVersion == null || !TEXT_VERSION.matcher(textVersion).matches()) {
			throw new InvalidRequestException(
					"consentTextVersion is required with the consent: the version of the consent text that was shown.");
		}
	}
}
