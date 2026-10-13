package com.dps.teknokent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.dto.requests.AddCoordinatorRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AddCoordinatorRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void needsAnAddressWithADottedDomain() {
		assertThat(validator.validate(new AddCoordinatorRequest(null, "Deniz Arı", "deniz@tohum"))).isNotEmpty();
		assertThat(validator.validate(new AddCoordinatorRequest(null, "Deniz Arı", "deniz@tohum.co"))).isEmpty();
	}
}
