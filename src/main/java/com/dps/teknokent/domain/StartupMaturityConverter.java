package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupMaturityConverter implements AttributeConverter<StartupMaturity, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable StartupMaturity attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable StartupMaturity convertToEntityAttribute(@Nullable String dbData) {
		return StartupMaturity.fromOrNull(dbData);
	}
}
