package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentKindConverterTest {

	private final DocumentKindConverter converter = new DocumentKindConverter();

	@ParameterizedTest
	@EnumSource(DocumentKind.class)
	void storesEveryKindAsTheValueTheColumnHasAlwaysHeldAndReadsItBack(DocumentKind kind) {
		String stored = converter.convertToDatabaseColumn(kind);

		assertThat(stored).isEqualTo(kind.value());
		assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(kind);
	}

	@Test
	void keepsTheValuesOfTheExistingRows() {
		assertThat(converter.convertToEntityAttribute("pitch-deck")).isEqualTo(DocumentKind.PITCH_DECK);
		assertThat(converter.convertToDatabaseColumn(DocumentKind.WEEKLY_PROGRAM)).isEqualTo("weekly-program");
	}

	@Test
	void leavesNullAsNull() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void refusesAStoredValueNoKindHas() {
		assertThatThrownBy(() -> converter.convertToEntityAttribute("PITCH_DECK"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PITCH_DECK");
	}
}
