package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupSectorConverter implements AttributeConverter<StartupSector, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable StartupSector attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable StartupSector convertToEntityAttribute(@Nullable String dbData) {
		return StartupSector.fromOrNull(dbData);
	}
}
