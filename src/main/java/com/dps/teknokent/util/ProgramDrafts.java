package com.dps.teknokent.util;

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.InvalidRequestException;

/** Only the admin and the program's coordinators see a draft, so nothing reaches anyone else before launch. */
public final class ProgramDrafts {

	private ProgramDrafts() {
	}

	public static void requireLaunched(Program program) {
		if (program.getStatus() == ProgramStatus.DRAFT) {
			throw new InvalidRequestException(Texts.forRequest("error.programNotLaunched"));
		}
	}
}
