package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** The "Kurucuyu değiştir" dialog: the startup, its founder now, and the members who may take over. */
public record FounderChangeOptionsResponse(
		UUID organizationId,
		String name,
		@Nullable Person currentFounder,
		List<Person> candidates) {

	/** No e-mail: the dialog names people only. */
	public record Person(UUID id, String fullName, @Nullable String jobTitle) {
	}
}
