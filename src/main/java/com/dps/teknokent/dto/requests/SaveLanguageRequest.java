package com.dps.teknokent.dto.requests;

import com.dps.teknokent.domain.AppLanguage;

import jakarta.validation.constraints.NotNull;

public record SaveLanguageRequest(@NotNull AppLanguage language) {
}
