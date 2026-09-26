package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;

class ProgramCoordinationTest {

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID BURCU = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

	private final ProgramRepository programs = mock(ProgramRepository.class);
	private final ProgramCoordinatorRepository coordinators = mock(ProgramCoordinatorRepository.class);

	@Test
	void aCoordinatorRunsWhatTheyCreatedAndWhatTheyWereAddedTo() {
		Program own = program("Kendi", ELIF, "2026-03-01T00:00:00Z");
		Program joined = program("Katıldığı", BURCU, "2026-05-01T00:00:00Z");
		when(programs.findByCreatedByOrderByCreatedAtDesc(ELIF)).thenReturn(List.of(own));
		when(coordinators.findByUserId(ELIF)).thenReturn(List.of(row(joined.getId(), ELIF)));
		when(programs.findAllById(List.of(joined.getId()))).thenReturn(List.of(joined));
		when(coordinators.existsByProgramIdAndUserId(joined.getId(), ELIF)).thenReturn(true);

		assertThat(ProgramCoordination.programsOf(ELIF, programs, coordinators))
				.extracting(Program::getName).containsExactly("Katıldığı", "Kendi");
		assertThat(ProgramCoordination.coordinates(own, ELIF, coordinators)).isTrue();
		assertThat(ProgramCoordination.coordinates(joined, ELIF, coordinators)).isTrue();
		assertThat(ProgramCoordination.coordinates(own, BURCU, coordinators)).isFalse();
	}

	@Test
	void theCreatorComesFirstAndIsNeverListedTwice() {
		Program program = program("METU", ELIF, "2026-02-01T00:00:00Z");
		when(coordinators.findByProgramIdOrderByCreatedAtAsc(program.getId()))
				.thenReturn(List.of(row(program.getId(), BURCU), row(program.getId(), ELIF)));

		assertThat(ProgramCoordination.coordinatorIds(program, coordinators)).containsExactly(ELIF, BURCU);
	}

	private static Program program(String name, UUID creator, String createdAt) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(name);
		program.setCreatedBy(creator);
		program.setCreatedAt(Instant.parse(createdAt));
		return program;
	}

	private static ProgramCoordinator row(UUID programId, UUID userId) {
		ProgramCoordinator row = new ProgramCoordinator();
		row.setProgramId(programId);
		row.setUserId(userId);
		return row;
	}
}
