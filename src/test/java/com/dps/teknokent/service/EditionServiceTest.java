package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.Edition;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.exception.InvalidRequestException;

class EditionServiceTest {

	@Test
	void readsTheConfiguredEdition() {
		assertThat(new EditionService("TEKNOKENT").current()).isEqualTo(Edition.TEKNOKENT);
		assertThat(new EditionService("odtu").current()).isEqualTo(Edition.ODTU);
	}

	@Test
	void refusesABlankEditionAtStartup() {
		assertThatThrownBy(() -> new EditionService("")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theTeknokentEditionTakesOnlyStartupPrograms() {
		EditionService service = new EditionService("TEKNOKENT");

		assertThatCode(() -> service.requireProgramType(ProgramType.STARTUP_MENTORING)).doesNotThrowAnyException();
		assertThatThrownBy(() -> service.requireProgramType(ProgramType.PEER_MENTORING))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.requireProgramType(ProgramType.OFFICE_HOURS))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theOdtuEditionTakesOnlyPeerAndOfficeHourPrograms() {
		EditionService service = new EditionService("ODTU");

		assertThatCode(() -> service.requireProgramType(ProgramType.PEER_MENTORING)).doesNotThrowAnyException();
		assertThatCode(() -> service.requireProgramType(ProgramType.OFFICE_HOURS)).doesNotThrowAnyException();
		assertThatThrownBy(() -> service.requireProgramType(ProgramType.STARTUP_MENTORING))
				.isInstanceOf(InvalidRequestException.class);
	}
}
