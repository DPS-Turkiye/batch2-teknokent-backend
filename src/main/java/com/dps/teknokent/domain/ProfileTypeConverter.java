package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link ProfileType} as its lowercase value, the form every existing row and index uses. */
@Converter(autoApply = false)
public class ProfileTypeConverter implements AttributeConverter<ProfileType, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable ProfileType attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable ProfileType convertToEntityAttribute(@Nullable String dbData) {
		if (dbData == null) {
			return null;
		}
		ProfileType type = ProfileType.fromValueOrNull(dbData);
		if (type == null) {
			throw new IllegalStateException("Unknown profile_type in the database: " + dbData);
		}
		return type;
	}
}
