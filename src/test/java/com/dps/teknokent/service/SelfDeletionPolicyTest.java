package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.AccountDeletionBlock;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.security.institution.OtgLoginProvider;

@ExtendWith(MockitoExtension.class)
class SelfDeletionPolicyTest {

	private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private InstitutionPersonRepository institutionPersonRepository;
	@Mock
	private AppUserIdentityRepository identityRepository;

	private SelfDeletionPolicy policy;
	private AppUser person;

	@BeforeEach
	void setUp() {
		policy = new SelfDeletionPolicy(organizationRepository, institutionPersonRepository, identityRepository,
				Clock.fixed(NOW, ZoneOffset.UTC));
		person = new AppUser();
		person.setId(UUID.randomUUID());
		person.setRole(UserRole.MENTEE);
		person.setStatus(UserAccountStatus.APPROVED);
	}

	@Test
	void acceptsTheConfirmationWordHoweverItIsCasedOrTyped() {
		for (String typed : new String[] { "SİL", "sil", "Sil", "SIL", "  SİL " }) {
			assertThat(SelfDeletionPolicy.confirmsAccount(typed)).as(typed).isTrue();
		}
		for (String typed : new String[] { "", "SILL", "DELETE", "S İ L", "SİLİN" }) {
			assertThat(SelfDeletionPolicy.confirmsAccount(typed)).as(typed).isFalse();
		}
		assertThat(SelfDeletionPolicy.confirmsAccount(null)).isFalse();
	}

	@Test
	void theStartupNameMustMatchExactlyApartFromSurroundingSpaces() {
		assertThat(SelfDeletionPolicy.confirmsStartup(" Döngü Labs ", "Döngü Labs")).isTrue();
		assertThat(SelfDeletionPolicy.confirmsStartup("döngü labs", "Döngü Labs")).isFalse();
		assertThat(SelfDeletionPolicy.confirmsStartup("Dongu Labs", "Döngü Labs")).isFalse();
		assertThat(SelfDeletionPolicy.confirmsStartup(null, "Döngü Labs")).isFalse();
	}

	@Test
	void asksForASignInFromTheLastFifteenMinutes() {
		assertThatCode(() -> policy.requireRecentSignIn(NOW.minusSeconds(15 * 60))).doesNotThrowAnyException();
		assertThatCode(() -> policy.requireRecentSignIn(NOW.plusSeconds(30))).doesNotThrowAnyException();

		for (Instant stale : new Instant[] { NOW.minusSeconds(15 * 60 + 1), NOW.plusSeconds(5 * 60), null }) {
			assertThatThrownBy(() -> policy.requireRecentSignIn(stale))
					.isInstanceOf(ConflictException.class)
					.extracting(error -> ((ConflictException) error).code())
					.isEqualTo(SelfDeletionPolicy.CODE_REAUTH);
		}
	}

	@Test
	void coordinatorsAndAdminsCannotDeleteThemselvesByRoleOrByGrant() {
		person.setRole(UserRole.COORDINATOR);
		assertThat(policy.accountBlock(person)).contains(AccountDeletionBlock.STAFF);
		assertThatThrownBy(() -> policy.requireAccountDeletable(person)).isInstanceOf(ResponseStatusException.class);

		person.setRole(UserRole.ADMIN);
		assertThat(policy.accountBlock(person)).contains(AccountDeletionBlock.STAFF);
	}

	@Test
	void aFounderWithALiveStartupMustDeleteItFirst() {
		when(organizationRepository.existsByFounderIdAndArchivedAtIsNullAndPersonalFalse(person.getId()))
				.thenReturn(true);

		assertThat(policy.accountBlock(person)).contains(AccountDeletionBlock.FOUNDER);
		assertThatThrownBy(() -> policy.requireAccountDeletable(person))
				.isInstanceOf(ConflictException.class)
				.extracting(error -> ((ConflictException) error).code())
				.isEqualTo(SelfDeletionPolicy.CODE_FOUNDER);
	}

	@Test
	void anAccountTiedToInstitutionRecordsIsRefused() {
		when(identityRepository.existsByUserIdAndProviderNot(person.getId(), OtgLoginProvider.ID)).thenReturn(true);

		assertThat(policy.accountBlock(person)).contains(AccountDeletionBlock.INSTITUTION);
		assertThatThrownBy(() -> policy.requireAccountDeletable(person))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aMenteeOrMentorWithoutAStartupMayDeleteTheirAccount() {
		assertThat(policy.accountBlock(person)).isEmpty();
		person.setRole(UserRole.MENTOR);
		assertThat(policy.accountBlock(person)).isEmpty();
	}

	@Test
	void onlyTheFounderOfALiveTeamMayDeleteIt() {
		Organization startup = new Organization();
		startup.setId(UUID.randomUUID());
		startup.setName("Döngü Labs");
		startup.setFounder(person);
		person.setOrganization(startup);
		assertThat(policy.deletableStartup(person)).contains(startup);

		AppUser member = new AppUser();
		member.setId(UUID.randomUUID());
		member.setRole(UserRole.MENTEE);
		member.setStatus(UserAccountStatus.APPROVED);
		member.setOrganization(startup);
		assertThat(policy.deletableStartup(member)).isEmpty();

		startup.setPersonal(true);
		assertThat(policy.deletableStartup(person)).isEmpty();
		startup.setPersonal(false);
		startup.setArchivedAt(NOW);
		assertThat(policy.deletableStartup(person)).isEmpty();
	}
}
