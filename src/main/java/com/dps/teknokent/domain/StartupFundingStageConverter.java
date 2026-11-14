package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class StartupFundingStageConverter implements AttributeConverter<StartupFundingStage, String> {

	@Override
	public @Nullable String convertToDatabaseColumn(@Nullable StartupFundingStage attribute) {
		return attribute == null ? null : attribute.value();
	}

	@Override
	public @Nullable StartupFundingStage convertToEntityAttribute(@Nullable String dbData) {
		return StartupFundingStage.fromOrNull(dbData);
	}
}
