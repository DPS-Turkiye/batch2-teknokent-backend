package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.CoordinatorSearchResult;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;

@ExtendWith(MockitoExtension.class)
class CoordinatorSearchServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@InjectMocks
	private CoordinatorSearchService service;

	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

	@Test
	void refusesAnyoneWhoIsNotAnAdmin() {
		for (UserRole role : List.of(UserRole.COORDINATOR, UserRole.MENTOR, UserRole.MENTEE)) {
			UUID id = UUID.randomUUID();
			when(domainLookupService.requireUser(id)).thenReturn(user(id, role, "x@x.org"));

			assertThatThrownBy(() -> service.search(id, "ayse", null))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							refusal -> assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		}
		verifyNoInteractions(appUserRepository);
	}

	@Test
	void refusesFewerThanThreeCharactersAfterTrimming() {
		stubAdmin();

		assertThatThrownBy(() -> service.search(ADMIN_ID, "  ay  ", null)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.search(ADMIN_ID, null, null)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.search(ADMIN_ID, "a".repeat(256), null))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(appUserRepository);
	}

	@Test
	void searchesApprovedCoordinatorsByLowercaseEscapedPrefixAndAtMostTen() {
		stubAdmin();
		UUID id = UUID.randomUUID();
		when(appUserRepository.findByPermissionAndStatusAndEmailPrefix(
				UserRole.COORDINATOR, UserAccountStatus.APPROVED, "ay\\_se", PageRequest.of(0, 10)))
				.thenReturn(List.of(user(id, UserRole.COORDINATOR, "ay_se@x.org")));

		assertThat(service.search(ADMIN_ID, " AY_SE ", null))
				.containsExactly(new CoordinatorSearchResult(id, "Name ay_se@x.org", "ay_se@x.org"));
	}

	@Test
	void leavesOutWhoAlreadyRunsTheProgramAndStillReturnsTen() {
		stubAdmin();
		UUID creator = UUID.randomUUID();
		UUID added = UUID.randomUUID();
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setCreatedBy(creator);
		ProgramCoordinator row = new ProgramCoordinator();
		row.setProgramId(PROGRAM_ID);
		row.setUserId(added);
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
		when(programCoordinatorRepository.findByProgramIdOrderByCreatedAtAsc(PROGRAM_ID)).thenReturn(List.of(row));
		List<AppUser> found = new ArrayList<>();
		found.add(user(creator, UserRole.COORDINATOR, "coord-0@x.org"));
		found.add(user(added, UserRole.COORDINATOR, "coord-1@x.org"));
		for (int i = 2; i < 12; i++) {
			found.add(user(UUID.randomUUID(), UserRole.COORDINATOR, "coord-" + i + "@x.org"));
		}
		when(appUserRepository.findByPermissionAndStatusAndEmailPrefix(
				eq(UserRole.COORDINATOR), eq(UserAccountStatus.APPROVED), eq("coord"), eq(PageRequest.of(0, 12))))
				.thenReturn(found);

		List<CoordinatorSearchResult> results = service.search(ADMIN_ID, "coord", PROGRAM_ID);

		assertThat(results).hasSize(10);
		assertThat(results).extracting(CoordinatorSearchResult::id).doesNotContain(creator, added);
	}

	@Test
	void anUnknownProgramIsNotFound() {
		stubAdmin();
		when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.search(ADMIN_ID, "coord", PROGRAM_ID))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(appUserRepository, never())
				.findByPermissionAndStatusAndEmailPrefix(any(), any(), anyString(), any());
	}

	private void stubAdmin() {
		when(domainLookupService.requireUser(ADMIN_ID)).thenReturn(user(ADMIN_ID, UserRole.ADMIN, "admin@x.org"));
	}

	private static AppUser user(UUID id, UserRole role, String email) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setEmail(email);
		user.setFullName("Name " + email);
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}
}
