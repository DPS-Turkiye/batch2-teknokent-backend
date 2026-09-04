package com.dps.teknokent.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupSectorConverter implements AttributeConverter<StartupSector, String> {

	@Override
	public String convertToDatabaseColumn(StartupSector attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public StartupSector convertToEntityAttribute(String dbData) {
		return StartupSector.fromOrNull(dbData);
	}
}
