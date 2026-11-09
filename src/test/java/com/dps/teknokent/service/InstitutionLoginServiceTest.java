package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
import com.dps.teknokent.entity.AppUserPermission;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.security.institution.InstitutionIdentity;
import com.dps.teknokent.security.institution.InstitutionLoginProvider;

class InstitutionLoginServiceTest {

	private final FakeProvider provider = new FakeProvider();
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final AppUserIdentityRepository identities = mock(AppUserIdentityRepository.class);
	private final InstitutionPersonRepository people = mock(InstitutionPersonRepository.class);
	private final LoginProvisioner loginProvisioner = mock(LoginProvisioner.class);
	private InstitutionLoginService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		ObjectProvider<InstitutionLoginProvider> providers = mock(ObjectProvider.class);
		when(providers.orderedStream()).thenAnswer(invocation -> Stream.of(provider));
		when(redis.opsForValue()).thenReturn(values);
		when(users.save(any(AppUser.class))).thenAnswer(invocation -> {
			AppUser user = invocation.getArgument(0);
			if (user.getId() == null) {
				user.setId(UUID.randomUUID());
			}
			return user;
		});
		when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
		when(identities.findByProviderAndPersistentId(anyString(), anyString())).thenReturn(Optional.empty());
		when(people.findByInstitutionAndPersistentId(anyString(), anyString())).thenReturn(Optional.empty());
		service = new InstitutionLoginService(providers, redis, users, identities, people, loginProvisioner);
	}

	@Test
	void startRemembersTheStateForTenMinutes() {
		var start = service.start("odtu");

		assertThat(start.redirectUrl()).isEqualTo("/fake-login?state=" + start.state());
		assertThat(start.state()).hasSizeGreaterThan(40);
		verify(values).set(InstitutionLoginService.STATE_KEY + start.state(), "odtu", Duration.ofMinutes(10));
	}

	@Test
	void anUnknownProviderIsNotFound() {
		assertThatThrownBy(() -> service.start("bogazici")).isInstanceOf(ResourceNotFoundException.class);
	}

	// The state is consumed on use, so a replayed or forged callback finds nothing.
	@Test
	void aCallbackWithoutItsStateIsRefused() {
		when(values.getAndDelete(anyString())).thenReturn(null);

		assertThatThrownBy(() -> service.complete("odtu", "made-up", Map.of()))
				.isInstanceOf(InvalidRequestException.class);
		verify(users, never()).save(any());
	}

	@Test
	void aCallbackIssuesASingleUseCodeForTheAccount() {
		when(values.getAndDelete(InstitutionLoginService.STATE_KEY + "s1")).thenReturn("odtu");
		provider.next = student("e100001", "ayse@metu.edu.tr");

		String code = service.complete("odtu", "s1", Map.of());

		ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
		verify(values).set(eq(InstitutionLoginService.CODE_KEY + code), userId.capture(), eq(Duration.ofSeconds(60)));
		assertThat(UUID.fromString(userId.getValue())).isNotNull();
	}

	@Test
	void theCodeIsTradedOnceForACustomToken() {
		AppUser user = account(UserRole.MENTEE);
		user.setFirebaseUid("odtu_e100001");
		when(values.getAndDelete(InstitutionLoginService.CODE_KEY + "c1")).thenReturn(user.getId().toString());
		when(users.findById(user.getId())).thenReturn(Optional.of(user));
		when(loginProvisioner.customToken(eq("odtu_e100001"), anyString(), anyString())).thenReturn("custom-token");

		assertThat(service.exchange("c1")).isEqualTo("custom-token");
		when(values.getAndDelete(InstitutionLoginService.CODE_KEY + "c1")).thenReturn(null);
		assertThatThrownBy(() -> service.exchange("c1")).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aNewStudentIsAnApprovedMenteeFromTheInstitution() {
		AppUser user = service.signIn(provider, student("e100001", " Ayse@METU.edu.tr "));

		assertThat(user.getEmail()).isEqualTo("ayse@metu.edu.tr");
		assertThat(user.getStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(user.getRole()).isEqualTo(UserRole.MENTEE);
		assertThat(user.activePermissions()).containsExactly(UserRole.MENTEE);
		assertThat(grants(user)).singleElement()
				.satisfies(grant -> assertThat(grant.getSource()).isEqualTo(PermissionSource.INSTITUTION));
		assertThat(user.getFirebaseUid()).isEqualTo("odtu_e100001");

		ArgumentCaptor<AppUserIdentity> link = ArgumentCaptor.forClass(AppUserIdentity.class);
		verify(identities).save(link.capture());
		assertThat(link.getValue().getPersistentId()).isEqualTo("e100001");
		ArgumentCaptor<InstitutionPerson> record = ArgumentCaptor.forClass(InstitutionPerson.class);
		verify(people).save(record.capture());
		assertThat(record.getValue().getUser()).isSameAs(user);
		assertThat(record.getValue().getSource()).isEqualTo(InstitutionDataSource.LOGIN);
		assertThat(record.getValue().getDepartmentName()).isEqualTo("Bilgisayar Mühendisliği");
	}

	// D7: one account, both permissions; the academician workspace opens first (A9).
	@Test
	void aResearchAssistantHoldsBothPermissionsOnOneAccount() {
		AppUser user = service.signIn(provider,
				identity("e200001", "elif@metu.edu.tr", true, Set.of(IdentityKind.STUDENT, IdentityKind.ACADEMIC)));

		assertThat(user.activePermissions()).containsExactlyInAnyOrder(UserRole.MENTEE, UserRole.ACADEMICIAN);
		assertThat(user.getRole()).isEqualTo(UserRole.ACADEMICIAN);
	}

	@Test
	void staffWithoutAStudentOrAcademicRecordGetNoAccount() {
		assertThatThrownBy(() -> service.signIn(provider,
				identity("p1", "ali@metu.edu.tr", true, Set.of(IdentityKind.STAFF))))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		verify(users, never()).save(any());
	}

	@Test
	void aReturningPersonLandsOnTheSameAccountAndLosesWhatTheRecordNoLongerBacks() {
		AppUser user = account(UserRole.ACADEMICIAN);
		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, Instant.now());
		user.setFirebaseUid("odtu_e200001");
		when(identities.findByProviderAndPersistentId("odtu", "e200001"))
				.thenReturn(Optional.of(new AppUserIdentity(user, "odtu", "e200001", Instant.now())));

		AppUser again = service.signIn(provider,
				identity("e200001", "elif@metu.edu.tr", true, Set.of(IdentityKind.ACADEMIC)));

		assertThat(again).isSameAs(user);
		assertThat(again.activePermissions()).containsExactly(UserRole.ACADEMICIAN);
		verify(identities, never()).save(any());
	}

	// D6: a founder who is also an ODTÜ student keeps one account with both permissions and their own login.
	@Test
	void aVerifiedAddressLinksToTheExistingAccount() {
		AppUser founder = account(UserRole.MENTEE);
		founder.setFirebaseUid("firebase-email-login");
		when(users.findByEmailIgnoreCase("ayse@metu.edu.tr")).thenReturn(Optional.of(founder));

		AppUser user = service.signIn(provider, student("e100001", "ayse@metu.edu.tr"));

		assertThat(user).isSameAs(founder);
		assertThat(user.getFirebaseUid()).isEqualTo("firebase-email-login");
		assertThat(grants(user)).singleElement()
				.satisfies(grant -> assertThat(grant.getSource()).isNotEqualTo(PermissionSource.INSTITUTION));
	}

	@Test
	void anUnverifiedAddressNeverTakesOverAnAccount() {
		when(users.findByEmailIgnoreCase("ayse@metu.edu.tr")).thenReturn(Optional.of(account(UserRole.MENTEE)));

		assertThatThrownBy(() -> service.signIn(provider,
				identity("e100001", "ayse@metu.edu.tr", false, Set.of(IdentityKind.STUDENT))))
				.isInstanceOf(ConflictException.class);
		verify(identities, never()).save(any());
	}

	@Test
	void staffAccountsKeepTheirOwnLogin() {
		when(users.findByEmailIgnoreCase("koordinator@metu.edu.tr"))
				.thenReturn(Optional.of(account(UserRole.COORDINATOR)));

		assertThatThrownBy(() -> service.signIn(provider, student("e100002", "koordinator@metu.edu.tr")))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void anAccountAlreadyLinkedToAnotherLoginIsNotLinkedTwice() {
		AppUser existing = account(UserRole.MENTEE);
		when(users.findByEmailIgnoreCase("ayse@metu.edu.tr")).thenReturn(Optional.of(existing));
		when(identities.existsByUserIdAndProvider(existing.getId(), "odtu")).thenReturn(true);

		assertThatThrownBy(() -> service.signIn(provider, student("e999999", "ayse@metu.edu.tr")))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void theProvidersExtraGrantsComeOnTop() {
		provider.extra = Set.of(UserRole.MENTOR);

		AppUser user = service.signIn(provider, student("e100003", "zeynep@metu.edu.tr"));

		assertThat(user.activePermissions()).containsExactlyInAnyOrder(UserRole.MENTEE, UserRole.MENTOR);
	}

	@Test
	void aMissingIdIsRefused() {
		assertThatThrownBy(() -> service.signIn(provider,
				identity(" ", "x@metu.edu.tr", true, Set.of(IdentityKind.STUDENT))))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static InstitutionIdentity student(String persistentId, String email) {
		return identity(persistentId, email, true, Set.of(IdentityKind.STUDENT));
	}

	private static InstitutionIdentity identity(String persistentId, String email, boolean verified,
			Set<IdentityKind> kinds) {
		return new InstitutionIdentity(persistentId, email, verified, "Test Kişi", kinds, "Mühendislik Fakültesi",
				"CENG", "Bilgisayar Mühendisliği", 3, null, null, false);
	}

	private static AppUser account(UserRole role) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setEmail(role.name().toLowerCase() + "@metu.edu.tr");
		user.setFullName("Var Olan");
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}

	@SuppressWarnings("unchecked")
	private static List<AppUserPermission> grants(AppUser user) {
		return (List<AppUserPermission>) ReflectionTestUtils.getField(user, "permissions");
	}

	private static final class FakeProvider implements InstitutionLoginProvider {
		private InstitutionIdentity next;
		private Set<UserRole> extra = Set.of();

		@Override
		public String id() {
			return "odtu";
		}

		@Override
		public String startLogin(String state) {
			return "/fake-login?state=" + state;
		}

		@Override
		public InstitutionIdentity completeLogin(Map<String, String> callback) {
			return next;
		}

		@Override
		public Set<UserRole> extraGrants(String persistentId) {
			return extra;
		}
	}
}
