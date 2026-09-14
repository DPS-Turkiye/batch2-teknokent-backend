package com.dps.teknokent.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupFundingStageConverter implements AttributeConverter<StartupFundingStage, String> {

	@Override
	public String convertToDatabaseColumn(StartupFundingStage attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public StartupFundingStage convertToEntityAttribute(String dbData) {
		return StartupFundingStage.fromOrNull(dbData);
	}
}
