package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;

class InviteCodeCheckServiceTest {

	private final OrganizationRepository organizations = mock(OrganizationRepository.class);
	private final ProgramTeamInviteRepository invites = mock(ProgramTeamInviteRepository.class);
	private final ProgramRepository programs = mock(ProgramRepository.class);
	private final RateLimitingConfig limits = new RateLimitingConfig();

	@Test
	void refusesOnceTheSharedBudgetIsSpentWhateverTheAddress() {
		emptyRepositories();
		InviteCodeCheckService service = service(2);

		assertThat(service.check("AAAA2345", "10.0.0.1").valid()).isFalse();
		assertThat(service.check("BBBB2345", "10.0.0.2").valid()).isFalse();
		assertTooMany(() -> service.check("CCCC2345", "10.0.0.3"));
	}

	@Test
	void oneVisitorRunsOutBeforeOthersDo() {
		emptyRepositories();
		InviteCodeCheckService service = service(1_000);

		for (int i = 0; i < InviteCodeCheckService.PER_ADDRESS; i++) {
			service.check("AAAA2345", "10.0.0.9");
		}
		assertTooMany(() -> service.check("AAAA2345", "10.0.0.9"));
		assertThat(service.check("AAAA2345", "10.0.0.10").valid()).isFalse();
	}

	@Test
	void aVisitorOverItsOwnBudgetSpendsNothingOfTheSharedOne() {
		emptyRepositories();
		InviteCodeCheckService service = service(InviteCodeCheckService.PER_ADDRESS + 1);

		for (int i = 0; i < InviteCodeCheckService.PER_ADDRESS; i++) {
			service.check("AAAA2345", "10.0.0.20");
		}
		for (int i = 0; i < 5; i++) {
			assertTooMany(() -> service.check("AAAA2345", "10.0.0.20"));
		}
		assertThat(service.check("AAAA2345", "10.0.0.21").valid()).isFalse();
	}

	@Test
	void aBlankCodeIsNotValidWithoutALookup() {
		InviteCodeCheckService service = service(10);

		assertThat(service.check("   ", null).valid()).isFalse();
		verifyNoInteractions(organizations, invites, programs);
	}

	private InviteCodeCheckService service(long perMinute) {
		return new InviteCodeCheckService(organizations, invites, programs, new EditionService("TEKNOKENT"), limits,
				perMinute);
	}

	private void emptyRepositories() {
		when(organizations.findByInviteCode(anyString())).thenReturn(Optional.empty());
		when(invites.findByCode(anyString())).thenReturn(Optional.empty());
	}

	private static void assertTooMany(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
				error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}
}
