package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ProgramEventRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramCalendarServiceTest {

	private static final UUID ACTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PROGRAM_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final Instant FROM = Instant.parse("2026-09-21T00:00:00Z");
	private static final Instant TO = Instant.parse("2026-09-28T00:00:00Z");

	@Mock
	private DomainLookupService domainLookupService;

	@Mock
	private ProgramStartupRepository programStartupRepository;

	@Mock
	private ProgramMentorRepository programMentorRepository;

	@Mock
	private ProgramEventRepository programEventRepository;

	@Mock
	private ProgramRepository programRepository;

	@InjectMocks
	private ProgramCalendarService service;

	@Test
	void aMenteeSeesTheEventsOfTheirStartupsPrograms() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTEE, organization()));
		ProgramStartup membership = new ProgramStartup();
		membership.setProgramId(PROGRAM_ID);
		membership.setOrganizationId(ORG_ID);
		when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(ORG_ID)).thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM, TO))
				.thenReturn(List.of(event()));

		var events = service.eventsFor(ACTOR_ID, FROM, TO);

		assertThat(events).singleElement().satisfies(item -> {
			assertThat(item.programName()).isEqualTo("METU Stars 2026");
			assertThat(item.title()).isEqualTo("Ara rapor teslimi");
			assertThat(item.kind()).isEqualTo(ProgramEventKind.DEADLINE);
		});
	}

	@Test
	void aMentorSeesOnlyProgramsTheyWereAcceptedInto() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTOR, null));
		ProgramMentor membership = new ProgramMentor();
		membership.setProgramId(PROGRAM_ID);
		when(programMentorRepository.findByMentorIdAndStatusOrderByCreatedAtDesc(ACTOR_ID, ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(membership));
		when(programRepository.findAllById(List.of(PROGRAM_ID))).thenReturn(List.of(program()));
		when(programEventRepository.findByProgramIdInAndStartsAtBetweenOrderByStartsAtAsc(List.of(PROGRAM_ID), FROM, TO))
				.thenReturn(List.of(event()));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).hasSize(1);
	}

	@Test
	void aMenteeWithoutAStartupSeesNothing() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.MENTEE, null));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).isEmpty();
		verifyNoInteractions(programEventRepository);
	}

	@Test
	void aCoordinatorUsesTheirOwnCalendarInstead() {
		when(domainLookupService.requireUser(ACTOR_ID)).thenReturn(user(UserRole.COORDINATOR, null));

		assertThat(service.eventsFor(ACTOR_ID, FROM, TO)).isEmpty();
		verifyNoInteractions(programEventRepository);
	}

	@Test
	void rejectsAnInvertedOrTooLongRange() {
		assertThatThrownBy(() -> service.eventsFor(ACTOR_ID, TO, FROM))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.eventsFor(ACTOR_ID, FROM, FROM.plusSeconds(200L * 86_400)))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(domainLookupService);
	}

	private static AppUser user(UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setId(ACTOR_ID);
		user.setRole(role);
		user.setOrganization(organization);
		return user;
	}

	private static Organization organization() {
		Organization organization = new Organization();
		organization.setId(ORG_ID);
		return organization;
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("METU Stars 2026");
		return program;
	}

	private static ProgramEvent event() {
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(PROGRAM_ID);
		event.setTitle("Ara rapor teslimi");
		event.setKind(ProgramEventKind.DEADLINE);
		event.setStartsAt(Instant.parse("2026-09-25T20:59:00Z"));
		return event;
	}
}
