package com.dps.teknokent.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupProgramLevelConverter implements AttributeConverter<StartupProgramLevel, String> {

	@Override
	public String convertToDatabaseColumn(StartupProgramLevel attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public StartupProgramLevel convertToEntityAttribute(String dbData) {
		return StartupProgramLevel.fromOrNull(dbData);
	}
}
