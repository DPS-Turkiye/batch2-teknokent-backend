package com.dps.teknokent.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.InstitutionLoginStartResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
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
import com.dps.teknokent.security.institution.InstitutionRecords;
import com.dps.teknokent.util.Texts;

/**
 * Turns an institution's sign-in into an ordinary Firebase session: links or creates the account, gives it what the
 * institution's record backs (student → MENTEE, academic → ACADEMICIAN), then hands the browser a one-time code it
 * trades for a Firebase custom token. Everything after that (bearer filter, /me, the session cookie) is unchanged.
 */
@Service
public class InstitutionLoginService {

	static final Duration STATE_TTL = Duration.ofMinutes(10);
	static final Duration CODE_TTL = Duration.ofSeconds(60);
	static final String STATE_KEY = "institution-login:state:";
	static final String CODE_KEY = "institution-login:code:";
	/** Firebase uids are at most 128 characters; the provider prefix takes the rest. */
	private static final int MAX_PERSISTENT_ID = 100;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final ObjectProvider<InstitutionLoginProvider> providers;
	private final StringRedisTemplate redis;
	private final AppUserRepository users;
	private final AppUserIdentityRepository identities;
	private final InstitutionPersonRepository people;
	private final LoginProvisioner loginProvisioner;

	public InstitutionLoginService(ObjectProvider<InstitutionLoginProvider> providers, StringRedisTemplate redis,
			AppUserRepository users, AppUserIdentityRepository identities, InstitutionPersonRepository people,
			LoginProvisioner loginProvisioner) {
		this.providers = providers;
		this.redis = redis;
		this.users = users;
		this.identities = identities;
		this.people = people;
		this.loginProvisioner = loginProvisioner;
	}

	public InstitutionLoginStartResponse start(String providerId) {
		InstitutionLoginProvider provider = provider(providerId);
		String state = randomToken();
		redis.opsForValue().set(STATE_KEY + state, provider.id(), STATE_TTL);
		return new InstitutionLoginStartResponse(provider.startLogin(state), state);
	}

	/** The state is single use and must have come from {@link #start} for this provider. */
	@Transactional
	public String complete(String providerId, String state, Map<String, String> callback) {
		InstitutionLoginProvider provider = provider(providerId);
		String expected = state == null || state.isBlank() ? null : redis.opsForValue().getAndDelete(STATE_KEY + state);
		if (!provider.id().equals(expected)) {
			throw new InvalidRequestException(Texts.forRequest("error.institutionLoginExpired"));
		}
		AppUser user = signIn(provider, provider.completeLogin(callback == null ? Map.of() : callback));
		String code = randomToken();
		redis.opsForValue().set(CODE_KEY + code, user.getId().toString(), CODE_TTL);
		return code;
	}

	@Transactional(readOnly = true)
	public String exchange(String code) {
		String userId = code == null || code.isBlank() ? null : redis.opsForValue().getAndDelete(CODE_KEY + code);
		if (userId == null) {
			throw new InvalidRequestException(Texts.forRequest("error.institutionLoginExpired"));
		}
		AppUser user = users.findById(UUID.fromString(userId))
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
		if (user.getStatus() == UserAccountStatus.REJECTED) {
			throw noAccess();
		}
		return loginProvisioner.customToken(user.getFirebaseUid(), user.getEmail(), user.getFullName());
	}

	AppUser signIn(InstitutionLoginProvider provider, InstitutionIdentity identity) {
		Instant now = Instant.now();
		Set<UserRole> backed = backedPermissions(identity.kinds());
		if (backed.isEmpty()) {
			throw noAccess();
		}
		String persistentId = requirePersistentId(identity);
		Optional<AppUserIdentity> known = identities.findByProviderAndPersistentId(provider.id(), persistentId);
		AppUser user;
		if (known.isPresent()) {
			known.get().setLastLoginAt(now);
			user = known.get().getUser();
		} else {
			user = users.save(linkByEmail(provider, identity).orElseGet(() -> newUser(identity, backed)));
			identities.save(new AppUserIdentity(user, provider.id(), persistentId, now));
		}
		if (user.getStatus() == UserAccountStatus.REJECTED) {
			throw noAccess();
		}
		for (UserRole permission : backed) {
			user.grant(permission, PermissionSource.INSTITUTION, now);
		}
		user.withdraw(PermissionSource.INSTITUTION, backed, now);
		for (UserRole permission : provider.extraGrants(persistentId)) {
			user.grant(permission, PermissionSource.ADMIN, now);
		}
		if (user.getFirebaseUid() == null) {
			user.setFirebaseUid(provider.id() + "_" + persistentId);
		}
		user = users.save(user);
		InstitutionPerson person = people.findByInstitutionAndPersistentId(provider.id(), persistentId)
				.orElseGet(() -> new InstitutionPerson(provider.id(), persistentId));
		InstitutionRecords.apply(identity, person, now);
		person.setSource(InstitutionDataSource.LOGIN);
		person.setUser(user);
		people.save(person);
		return user;
	}

	/**
	 * Binds the institution login to an existing Menton account only when the institution vouches for the address
	 * (an unverified address would let anyone take over that account), never to an admin or coordinator account,
	 * and never to one already bound to another login at this institution.
	 */
	private Optional<AppUser> linkByEmail(InstitutionLoginProvider provider, InstitutionIdentity identity) {
		Optional<AppUser> holder = users.findByEmailIgnoreCase(requireEmail(identity));
		if (holder.isEmpty()) {
			return Optional.empty();
		}
		AppUser existing = holder.get();
		if (!identity.emailVerified()) {
			throw new ConflictException(Texts.forRequest("error.institutionEmailTaken"));
		}
		if (existing.has(UserRole.ADMIN) || existing.has(UserRole.COORDINATOR)) {
			throw new ConflictException(Texts.forRequest("error.institutionStaffAccount"));
		}
		if (identities.existsByUserIdAndProvider(existing.getId(), provider.id())) {
			throw new ConflictException(Texts.forRequest("error.institutionAlreadyLinked"));
		}
		return holder;
	}

	/** Institution accounts are never approved by hand (board 08): what the institution says is the approval. */
	private static AppUser newUser(InstitutionIdentity identity, Set<UserRole> backed) {
		AppUser user = new AppUser();
		user.setEmail(requireEmail(identity));
		user.setFullName(identity.fullName() == null || identity.fullName().isBlank()
				? requireEmail(identity)
				: identity.fullName().trim());
		user.setStatus(UserAccountStatus.APPROVED);
		user.setPrimaryRole(backed.contains(UserRole.ACADEMICIAN) ? UserRole.ACADEMICIAN : UserRole.MENTEE);
		return user;
	}

	static Set<UserRole> backedPermissions(Set<IdentityKind> kinds) {
		Set<UserRole> backed = EnumSet.noneOf(UserRole.class);
		if (kinds.contains(IdentityKind.STUDENT)) {
			backed.add(UserRole.MENTEE);
		}
		if (kinds.contains(IdentityKind.ACADEMIC)) {
			backed.add(UserRole.ACADEMICIAN);
		}
		return backed;
	}

	private static String requirePersistentId(InstitutionIdentity identity) {
		String persistentId = identity.persistentId() == null ? "" : identity.persistentId().trim();
		if (persistentId.isEmpty() || persistentId.length() > MAX_PERSISTENT_ID) {
			throw new InvalidRequestException("The institution sent no usable id for this person.");
		}
		return persistentId;
	}

	private static String requireEmail(InstitutionIdentity identity) {
		if (identity.email() == null || identity.email().isBlank()) {
			throw new InvalidRequestException("The institution sent no e-mail address for this person.");
		}
		return identity.email().trim().toLowerCase(Locale.ROOT);
	}

	private InstitutionLoginProvider provider(String providerId) {
		Map<String, InstitutionLoginProvider> byId = new LinkedHashMap<>();
		providers.orderedStream().forEach(provider -> byId.put(provider.id(), provider));
		InstitutionLoginProvider provider = byId.get(providerId);
		if (provider == null) {
			throw new ResourceNotFoundException("No institution login named " + providerId);
		}
		return provider;
	}

	private static ResponseStatusException noAccess() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.institutionNoAccess"));
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
