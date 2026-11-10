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
import org.springframework.transaction.PlatformTransactionManager;
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

import tools.jackson.databind.json.JsonMapper;

class InstitutionLoginServiceTest {

	private final FakeProvider provider = new FakeProvider("odtu", true);
	private final FakeProvider otg = new FakeProvider("otg", false);
	private final JsonMapper jsonMapper = JsonMapper.builder().build();
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
		when(providers.orderedStream()).thenAnswer(invocation -> Stream.of(provider, otg));
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
		service = new InstitutionLoginService(providers, redis, users, identities, people, loginProvisioner,
				mock(PlatformTransactionManager.class), jsonMapper);
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
		when(loginProvisioner.customToken(eq("odtu_e100001"), anyString(), anyString(), eq(false)))
				.thenReturn("custom-token");

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

	// OTG only proves who someone is. A newcomer gets no account: their code leads to an ordinary sign-up, where
	// founder / invite code and coordinator approval run as for everyone else.
	@Test
	void aNewcomerFromAnIdentityOnlyLoginGetsASignupCodeAndNoAccount() {
		when(values.getAndDelete(InstitutionLoginService.STATE_KEY + "s1")).thenReturn("otg");
		otg.next = otgPerson("a72ab40d", " Ogrenci@Ornek.com ", true, "Ayşe Yılmaz");

		String code = service.complete("otg", "s1", Map.of("code", "c"));

		ArgumentCaptor<String> login = ArgumentCaptor.forClass(String.class);
		verify(values).set(eq(InstitutionLoginService.SIGNUP_CODE_KEY + code), login.capture(),
				eq(Duration.ofSeconds(60)));
		assertThat(jsonMapper.readValue(login.getValue(), InstitutionLoginService.NewLogin.class))
				.isEqualTo(new InstitutionLoginService.NewLogin("otg_a72ab40d", "ogrenci@ornek.com", "Ayşe Yılmaz",
						true));
		verify(users, never()).save(any());
		verify(identities, never()).save(any());
		verify(people, never()).save(any());
	}

	@Test
	void aSignupCodeIsTradedOnceForALoginWithoutAnAccount() {
		String login = jsonMapper.writeValueAsString(
				new InstitutionLoginService.NewLogin("otg_a72ab40d", "ogrenci@ornek.com", null, true));
		when(values.getAndDelete(InstitutionLoginService.SIGNUP_CODE_KEY + "c1")).thenReturn(login);
		when(loginProvisioner.customToken("otg_a72ab40d", "ogrenci@ornek.com", null, true)).thenReturn("custom-token");

		assertThat(service.exchange("c1")).isEqualTo("custom-token");
		when(values.getAndDelete(InstitutionLoginService.SIGNUP_CODE_KEY + "c1")).thenReturn(null);
		assertThatThrownBy(() -> service.exchange("c1")).isInstanceOf(InvalidRequestException.class);
		verify(users, never()).save(any());
	}

	@Test
	void aKnownPersonFromAnIdentityOnlyLoginGetsAnAccountCode() {
		AppUser user = account(UserRole.MENTEE);
		when(identities.findByProviderAndPersistentId("otg", "a72ab40d"))
				.thenReturn(Optional.of(new AppUserIdentity(user, "otg", "a72ab40d", Instant.now())));
		when(values.getAndDelete(InstitutionLoginService.STATE_KEY + "s1")).thenReturn("otg");
		otg.next = otgPerson("a72ab40d", "ogrenci@ornek.com", true, null);

		String code = service.complete("otg", "s1", Map.of("code", "c"));

		verify(values).set(InstitutionLoginService.CODE_KEY + code, user.getId().toString(), Duration.ofSeconds(60));
		verify(values, never()).set(eq(InstitutionLoginService.SIGNUP_CODE_KEY + code), anyString(), any(Duration.class));
	}

	// Status, roles and grants stay Menton's: a PENDING account stays PENDING, and an ODTÜ grant is not withdrawn.
	@Test
	void anIdentityOnlyLoginChangesNoStatusOrGrant() {
		AppUser user = account(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.PENDING);
		user.grant(UserRole.ACADEMICIAN, PermissionSource.INSTITUTION, Instant.now());
		when(identities.findByProviderAndPersistentId("otg", "a72ab40d"))
				.thenReturn(Optional.of(new AppUserIdentity(user, "otg", "a72ab40d", Instant.now())));

		AppUser found = service.findAccount(otg, otgPerson("a72ab40d", "ogrenci@ornek.com", true, null)).orElseThrow();

		assertThat(found).isSameAs(user);
		assertThat(found.getStatus()).isEqualTo(UserAccountStatus.PENDING);
		assertThat(found.activePermissions()).containsExactlyInAnyOrder(UserRole.MENTEE, UserRole.ACADEMICIAN);
		verify(people, never()).save(any());
	}

	// After sign-up the account carries the Firebase uid the first OTG login made; the next login binds it.
	@Test
	void theAccountSignedUpAfterAnEarlierLoginIsBoundOnTheNextOne() {
		AppUser signedUp = account(UserRole.MENTEE);
		signedUp.setFirebaseUid("otg_a72ab40d");
		when(users.findByFirebaseUid("otg_a72ab40d")).thenReturn(Optional.of(signedUp));

		AppUser found = service.findAccount(otg, otgPerson("a72ab40d", "yeni@ornek.com", false, null)).orElseThrow();

		assertThat(found).isSameAs(signedUp);
		ArgumentCaptor<AppUserIdentity> link = ArgumentCaptor.forClass(AppUserIdentity.class);
		verify(identities).save(link.capture());
		assertThat(link.getValue().getProvider()).isEqualTo("otg");
		assertThat(link.getValue().getPersistentId()).isEqualTo("a72ab40d");
	}

	@Test
	void anIdentityOnlyLoginLinksAVerifiedAddressButNeverStaffOrAnUnverifiedOne() {
		AppUser founder = account(UserRole.MENTEE);
		when(users.findByEmailIgnoreCase("ogrenci@ornek.com")).thenReturn(Optional.of(founder));
		assertThat(service.findAccount(otg, otgPerson("a1", "ogrenci@ornek.com", true, null))).contains(founder);

		assertThatThrownBy(() -> service.findAccount(otg, otgPerson("a2", "ogrenci@ornek.com", false, null)))
				.isInstanceOf(ConflictException.class);

		when(users.findByEmailIgnoreCase("koordinator@ornek.com")).thenReturn(Optional.of(account(UserRole.COORDINATOR)));
		assertThatThrownBy(() -> service.findAccount(otg, otgPerson("a3", "koordinator@ornek.com", true, null)))
				.isInstanceOf(ConflictException.class);
	}

	@Test
	void anIdentityOnlyLoginDoesNotBindASecondPersonToAnAlreadyLinkedAccount() {
		AppUser signedUp = account(UserRole.MENTEE);
		when(users.findByFirebaseUid("otg_a72ab40d")).thenReturn(Optional.of(signedUp));
		when(identities.existsByUserIdAndProvider(signedUp.getId(), "otg")).thenReturn(true);

		assertThatThrownBy(() -> service.findAccount(otg, otgPerson("a72ab40d", "ogrenci@ornek.com", true, null)))
				.isInstanceOf(ConflictException.class);
		verify(identities, never()).save(any());
	}

	@Test
	void aRejectedAccountGetsNoCodeFromAnIdentityOnlyLogin() {
		AppUser user = account(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.REJECTED);
		when(identities.findByProviderAndPersistentId("otg", "a72ab40d"))
				.thenReturn(Optional.of(new AppUserIdentity(user, "otg", "a72ab40d", Instant.now())));

		assertThatThrownBy(() -> service.findAccount(otg, otgPerson("a72ab40d", "ogrenci@ornek.com", true, null)))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@Test
	void anIdentityOnlyLoginWithoutAnAddressIsRefused() {
		assertThatThrownBy(() -> service.findAccount(otg, otgPerson("a72ab40d", null, false, null)))
				.isInstanceOf(InvalidRequestException.class);
	}

	private static InstitutionIdentity otgPerson(String sub, String email, boolean verified, String name) {
		return new InstitutionIdentity(sub, email, verified, name, Set.of(), null, null, null, null, null, null, false);
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
		private final String id;
		private final boolean grantsAccess;
		private InstitutionIdentity next;
		private Set<UserRole> extra = Set.of();

		private FakeProvider(String id, boolean grantsAccess) {
			this.id = id;
			this.grantsAccess = grantsAccess;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean grantsAccess() {
			return grantsAccess;
		}

		@Override
		public String startLogin(String state) {
			return "/fake-login?state=" + state;
		}

		@Override
		public InstitutionIdentity completeLogin(String state, Map<String, String> callback) {
			return next;
		}

		@Override
		public Set<UserRole> extraGrants(String persistentId) {
			return extra;
		}
	}
}
