package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupProgramLevelConverter implements AttributeConverter<StartupProgramLevel, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable StartupProgramLevel attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable StartupProgramLevel convertToEntityAttribute(@Nullable String dbData) {
		return StartupProgramLevel.fromOrNull(dbData);
	}
}
