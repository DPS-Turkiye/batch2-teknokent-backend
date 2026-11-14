package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditionTest {

	@Test
	void eachEditionTakesOnlyItsOwnProgramTypes() {
		assertThat(Edition.TEKNOKENT.allows(ProgramType.STARTUP_MENTORING)).isTrue();
		assertThat(Edition.TEKNOKENT.allows(ProgramType.PEER_MENTORING)).isFalse();
		assertThat(Edition.TEKNOKENT.allows(ProgramType.OFFICE_HOURS)).isFalse();
		assertThat(Edition.ODTU.allows(ProgramType.PEER_MENTORING)).isTrue();
		assertThat(Edition.ODTU.allows(ProgramType.OFFICE_HOURS)).isTrue();
		assertThat(Edition.ODTU.allows(ProgramType.STARTUP_MENTORING)).isFalse();
		assertThat(Edition.ODTU.allows(null)).isFalse();
	}

	@Test
	void eachEditionClosesExactlyTheOtherOnesPaths() {
		assertThat(Edition.TEKNOKENT.closedPaths()).containsExactlyElementsOf(Edition.ODTU.ownPaths());
		assertThat(Edition.ODTU.closedPaths()).containsExactlyElementsOf(Edition.TEKNOKENT.ownPaths());
		assertThat(Edition.TEKNOKENT.ownPaths()).doesNotContainAnyElementsOf(Edition.ODTU.ownPaths());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ODTU", "odtu", " Odtu " })
	void parsesTheEditionWhateverItsCase(String value) {
		assertThat(Edition.parse(value)).isEqualTo(Edition.ODTU);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  ", "METU", "TEKNOKENT,ODTU" })
	void refusesABlankOrUnknownEdition(String value) {
		assertThatThrownBy(() -> Edition.parse(value))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("APP_EDITION");
	}

	@Test
	void refusesAMissingEdition() {
		assertThatThrownBy(() -> Edition.parse(null)).isInstanceOf(IllegalStateException.class);
	}
}
