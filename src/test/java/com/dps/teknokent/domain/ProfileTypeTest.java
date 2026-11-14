package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.exception.InvalidRequestException;

import tools.jackson.databind.ObjectMapper;

class ProfileTypeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final ProfileTypeConverter column = new ProfileTypeConverter();

	// The frontend, the stored rows and V10's partial index all use the lowercase values.
	@Test
	void travelsAndIsStoredAsTheLowercaseValueItAlwaysHad() {
		assertThat(mapper.writeValueAsString(ProfileType.INDIVIDUAL_MENTEE)).isEqualTo("\"individual_mentee\"");
		assertThat(mapper.readValue("\"mentee\"", ProfileType.class)).isEqualTo(ProfileType.MENTEE);
		assertThat(column.convertToDatabaseColumn(ProfileType.MENTEE)).isEqualTo("mentee");
		assertThat(column.convertToEntityAttribute("individual_mentee")).isEqualTo(ProfileType.INDIVIDUAL_MENTEE);
		assertThat(column.convertToDatabaseColumn(null)).isNull();
		assertThat(column.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void anUnknownValueInARequestIsTheCallersError() {
		assertThatThrownBy(() -> ProfileType.fromValue("investor"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("mentee");
		assertThatThrownBy(() -> ProfileType.fromValue("MENTEE")).isInstanceOf(InvalidRequestException.class);
		assertThat(ProfileType.fromValueOrNull(null)).isNull();
	}

	@Test
	void anUnknownStoredValueIsNotSilentlyReadAsAnother() {
		assertThatThrownBy(() -> column.convertToEntityAttribute("startup"))
				.isInstanceOf(IllegalStateException.class);
	}
}
