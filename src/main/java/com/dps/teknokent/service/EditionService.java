package com.dps.teknokent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.Edition;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.exception.InvalidRequestException;

/** The deployment's edition ({@code APP_EDITION}), read once at startup. */
@Service
public class EditionService {

	private final Edition edition;

	public EditionService(@Value("${app.edition:TEKNOKENT}") String edition) {
		this.edition = Edition.parse(edition);
	}

	public Edition current() {
		return edition;
	}

	/** Programs of the other edition's types can be neither created nor joined here. */
	public void requireProgramType(ProgramType type) {
		if (!current().allows(type)) {
			throw new InvalidRequestException("This program type is not available here.");
		}
	}
}
