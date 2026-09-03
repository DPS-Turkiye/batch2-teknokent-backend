package com.dps.teknokent.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupMaturityConverter implements AttributeConverter<StartupMaturity, String> {

	@Override
	public String convertToDatabaseColumn(StartupMaturity attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public StartupMaturity convertToEntityAttribute(String dbData) {
		return StartupMaturity.fromOrNull(dbData);
	}
}
