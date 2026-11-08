package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPeerCategory;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramPeerCategoryRepository;

@ExtendWith(MockitoExtension.class)
class ProgramPeerCategoryServiceTest {

	private static final UUID COORDINATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-000000000010");

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private ProgramPeerCategoryRepository categories;
	@InjectMocks
	private ProgramPeerCategoryService service;

	@Test
	void aNewPeerProgramHasNoActiveCategory() {
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, PROGRAM))
				.thenReturn(program(ProgramType.PEER_MENTORING));
		when(categories.findByProgramIdIn(List.of(PROGRAM))).thenReturn(List.of());

		assertThat(service.get(COORDINATOR, PROGRAM).active()).isEmpty();
	}

	@Test
	void replacingKeepsTheRowsStillWantedAddsTheNewOnesAndDropsTheRest() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR, PROGRAM))
				.thenReturn(program(ProgramType.PEER_MENTORING));
		ProgramPeerCategory kept = row(PeerTopicCategory.CAREER_INTERNSHIP);
		ProgramPeerCategory dropped = row(PeerTopicCategory.CAMPUS_LIFE);
		when(categories.findByProgramId(PROGRAM)).thenReturn(List.of(kept, dropped));

		var response = service.replace(COORDINATOR, PROGRAM, List.of(PeerTopicCategory.INTERNATIONAL,
				PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL));

		assertThat(response.active())
				.containsExactly(PeerTopicCategory.CAREER_INTERNSHIP, PeerTopicCategory.INTERNATIONAL);
		verify(categories).deleteAll(List.of(dropped));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ProgramPeerCategory>> added = ArgumentCaptor.forClass(List.class);
		verify(categories).saveAll(added.capture());
		assertThat(added.getValue()).singleElement().satisfies(row -> {
			assertThat(row.getCategory()).isEqualTo(PeerTopicCategory.INTERNATIONAL);
			assertThat(row.getProgramId()).isEqualTo(PROGRAM);
			assertThat(row.getActivatedBy()).isEqualTo(COORDINATOR);
		});
	}

	@Test
	void anEmptyListTurnsEveryCategoryOff() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR, PROGRAM))
				.thenReturn(program(ProgramType.PEER_MENTORING));
		ProgramPeerCategory current = row(PeerTopicCategory.CAREER_INTERNSHIP);
		when(categories.findByProgramId(PROGRAM)).thenReturn(List.of(current));

		assertThat(service.replace(COORDINATOR, PROGRAM, List.of()).active()).isEmpty();
		verify(categories).deleteAll(List.of(current));
	}

	@Test
	void onlyPeerProgramsHaveCategories() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR, PROGRAM))
				.thenReturn(program(ProgramType.STARTUP_MENTORING));

		assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM, List.of(PeerTopicCategory.CAMPUS_LIFE)))
				.isInstanceOf(InvalidRequestException.class);
		verify(categories, never()).saveAll(any());
	}

	@Test
	void theAdminOrAnArchivedProgramIsRefusedByTheSettingsGuard() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR, PROGRAM))
				.thenThrow(new InvalidRequestException("An admin views program settings"));

		assertThatThrownBy(() -> service.replace(COORDINATOR, PROGRAM, List.of(PeerTopicCategory.CAMPUS_LIFE)))
				.isInstanceOf(InvalidRequestException.class);
		verify(categories, never()).findByProgramId(any());
	}

	@Test
	void theUnionOverSeveralProgramsAndNothingForNoProgram() {
		UUID other = UUID.randomUUID();
		ProgramPeerCategory a = row(PeerTopicCategory.CAMPUS_LIFE);
		ProgramPeerCategory b = new ProgramPeerCategory(other, PeerTopicCategory.ACADEMIC_DEVELOPMENT, null, Instant.now());
		when(categories.findByProgramIdIn(List.of(PROGRAM, other))).thenReturn(List.of(a, b));

		assertThat(service.activeIn(List.of(PROGRAM, other)))
				.containsExactly(PeerTopicCategory.ACADEMIC_DEVELOPMENT, PeerTopicCategory.CAMPUS_LIFE);
		assertThat(service.activeIn(List.of())).isEmpty();
		verify(categories, never()).findByProgramIdIn(List.of());
	}

	private static Program program(ProgramType type) {
		Program program = new Program();
		program.setId(PROGRAM);
		program.setName("İŞKUR Akran 2026");
		program.setProgramType(type);
		return program;
	}

	private static ProgramPeerCategory row(PeerTopicCategory category) {
		return new ProgramPeerCategory(PROGRAM, category, COORDINATOR, Instant.parse("2026-11-01T10:00:00Z"));
	}
}
