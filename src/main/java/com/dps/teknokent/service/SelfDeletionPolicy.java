package com.dps.teknokent.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
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
import com.dps.teknokent.util.Texts;

/**
 * Who may delete their own account or startup, shared by the page that offers it and the deletions themselves, so
 * the buttons and the checks cannot drift apart.
 */
@Component
class SelfDeletionPolicy {

	static final String CODE_FOUNDER = "SELF_DELETE_FOUNDER";
	static final String CODE_REAUTH = "REAUTH_REQUIRED";
	static final String CODE_CONFIRMATION = "SELF_DELETE_CONFIRMATION";
	/**
	 * How recently the person must have entered their credentials. A token refresh does not count, so a laptop left
	 * signed in for days cannot delete the account it is signed in to.
	 */
	static final Duration RECENT_SIGN_IN = Duration.ofMinutes(15);
	/** Firebase stamps auth_time on its own clock; a sign-in a moment "in the future" is clock skew, not forgery. */
	private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
	private static final Locale TURKISH = Locale.forLanguageTag("tr");
	private static final String ACCOUNT_CONFIRMATION = "SİL";

	private final OrganizationRepository organizationRepository;
	private final InstitutionPersonRepository institutionPersonRepository;
	private final AppUserIdentityRepository identityRepository;
	private final Clock clock;

	@Autowired
	SelfDeletionPolicy(OrganizationRepository organizationRepository,
			InstitutionPersonRepository institutionPersonRepository, AppUserIdentityRepository identityRepository) {
		this(organizationRepository, institutionPersonRepository, identityRepository, Clock.systemUTC());
	}

	SelfDeletionPolicy(OrganizationRepository organizationRepository,
			InstitutionPersonRepository institutionPersonRepository, AppUserIdentityRepository identityRepository,
			Clock clock) {
		this.organizationRepository = organizationRepository;
		this.institutionPersonRepository = institutionPersonRepository;
		this.identityRepository = identityRepository;
		this.clock = clock;
	}

	/** Empty when the person may delete their account now. */
	Optional<AccountDeletionBlock> accountBlock(AppUser user) {
		if (isStaff(user)) {
			return Optional.of(AccountDeletionBlock.STAFF);
		}
		if (institutionPersonRepository.existsByUserId(user.getId())
				|| identityRepository.existsByUserIdAndProviderNot(user.getId(), OtgLoginProvider.ID)) {
			return Optional.of(AccountDeletionBlock.INSTITUTION);
		}
		if (organizationRepository.existsByFounderIdAndArchivedAtIsNullAndPersonalFalse(user.getId())) {
			return Optional.of(AccountDeletionBlock.FOUNDER);
		}
		return Optional.empty();
	}

	/** The startup this person may delete: the live, non-personal one they are in and lead. */
	Optional<Organization> deletableStartup(AppUser user) {
		Organization organization = user.getOrganization();
		if (user.getStatus() != UserAccountStatus.APPROVED || isStaff(user) || organization == null
				|| organization.isPersonal() || organization.isArchived() || organization.getFounder() == null
				|| !organization.getFounder().getId().equals(user.getId())) {
			return Optional.empty();
		}
		return Optional.of(organization);
	}

	void requireAccountDeletable(AppUser user) {
		Optional<AccountDeletionBlock> block = accountBlock(user);
		if (block.isEmpty()) {
			return;
		}
		switch (block.get()) {
		case STAFF -> throw new ResponseStatusException(HttpStatus.FORBIDDEN,
				Texts.forRequest("error.selfDeleteStaff"));
		case INSTITUTION -> throw new InvalidRequestException(Texts.forRequest("error.userDeleteInstitutionLinked"),
				UserDeletionService.CODE_INSTITUTION_LINKED);
		case FOUNDER -> throw new ConflictException(Texts.forRequest("error.selfDeleteFounder"), CODE_FOUNDER);
		}
	}

	void requireRecentSignIn(@Nullable Instant signedInAt) {
		Instant now = clock.instant();
		if (signedInAt == null || signedInAt.isBefore(now.minus(RECENT_SIGN_IN))
				|| signedInAt.isAfter(now.plus(CLOCK_SKEW))) {
			throw new ConflictException(Texts.forRequest("error.selfDeleteReauth"), CODE_REAUTH);
		}
	}

	/**
	 * The word typed into the account's second confirmation. Turkish upper-casing, with a dotless capital I taken as
	 * İ, so "sil", "Sil" and "SIL" from a keyboard without İ all count.
	 */
	static boolean confirmsAccount(@Nullable String typed) {
		return typed != null && typed.trim().toUpperCase(TURKISH).replace('I', 'İ').equals(ACCOUNT_CONFIRMATION);
	}

	/**
	 * The startup's name exactly, apart from surrounding spaces: case and diacritics count. Both sides are NFC, so a
	 * name pasted with a decomposed "ö" or "İ" still matches.
	 */
	static boolean confirmsStartup(@Nullable String typed, String name) {
		return typed != null && nfc(typed).equals(nfc(name));
	}

	private static String nfc(String text) {
		return Normalizer.normalize(text.trim(), Normalizer.Form.NFC);
	}

	private static boolean isStaff(AppUser user) {
		return user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.COORDINATOR
				|| user.has(UserRole.ADMIN) || user.has(UserRole.COORDINATOR);
	}
}
