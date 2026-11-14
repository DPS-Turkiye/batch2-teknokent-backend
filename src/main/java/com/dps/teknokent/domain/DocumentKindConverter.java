package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link DocumentKind} as its {@link DocumentKind#value()}, the strings the column has always held. */
@Converter(autoApply = false)
public class DocumentKindConverter implements AttributeConverter<DocumentKind, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable DocumentKind attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable DocumentKind convertToEntityAttribute(@Nullable String dbData) {
		if (dbData == null) {
			return null;
		}
		// Every row was written from DocumentKind.value(), so an unknown one is corrupt data, not a bad request.
		return DocumentKind.find(dbData)
				.orElseThrow(() -> new IllegalStateException("Unknown stored document kind: " + dbData));
	}
}
