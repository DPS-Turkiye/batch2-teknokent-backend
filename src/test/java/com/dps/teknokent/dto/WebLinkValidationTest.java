package com.dps.teknokent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.dps.teknokent.dto.requests.CreateStartupRequest;
import com.dps.teknokent.dto.requests.RegisterFounderRequest;
import com.dps.teknokent.dto.requests.UpdateOrganizationRequest;
import com.dps.teknokent.dto.requests.UpdateUserProfileRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** The profile links people type and others click: a script or data URL never gets stored. */
class WebLinkValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	static Stream<Arguments> linkFields() {
		return Stream.of(
				Arguments.of(UpdateUserProfileRequest.class, "linkedinUrl"),
				Arguments.of(UpdateOrganizationRequest.class, "website"),
				Arguments.of(UpdateOrganizationRequest.class, "mvpUrl"),
				Arguments.of(CreateStartupRequest.class, "website"),
				Arguments.of(CreateStartupRequest.class, "mvpUrl"),
				Arguments.of(RegisterFounderRequest.class, "linkedinUrl"),
				Arguments.of(RegisterFounderRequest.class, "website"),
				Arguments.of(RegisterFounderRequest.class, "mvpUrl"));
	}

	@ParameterizedTest
	@MethodSource("linkFields")
	void refusesScriptAndDataLinks(Class<?> request, String field) {
		for (String link : Arrays.asList("javascript:alert(document.cookie)", "JavaScript:alert(1)",
				" javascript:alert(1)", "data:text/html,<script>alert(1)</script>", "vbscript:msgbox(1)",
				"//evil.example/path", "https://ornek.com/a b")) {
			assertThat(validator.validateValue(request, field, link)).as(link).isNotEmpty();
		}
	}

	@ParameterizedTest
	@MethodSource("linkFields")
	void keepsWebAddressesAndEmptyFields(Class<?> request, String field) {
		for (String link : Arrays.asList(null, "", "   ", "https://www.linkedin.com/in/sude-arya",
				"http://ornek.com", "HTTPS://ORNEK.COM", "ornek.com", "linkedin.com/in/kullanici-adi",
				" https://ornek.com/mvp?ref=a:b ", "https://ornek.com:8443/demo")) {
			assertThat(validator.validateValue(request, field, link)).as(String.valueOf(link)).isEmpty();
		}
	}
}
