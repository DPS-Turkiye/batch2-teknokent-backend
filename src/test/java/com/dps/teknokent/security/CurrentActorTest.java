package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.exception.InvalidRequestException;

class CurrentActorTest {

	private static final UUID SIGNED_IN = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID CLAIMED = UUID.fromString("20000000-0000-0000-0000-000000000002");

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void prefersTheVerifiedBearerUserOverTheClaimedActor() {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new AppUserPrincipal(SIGNED_IN, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED, "uid"),
				null, List.of()));

		assertThat(CurrentActor.resolve(CLAIMED)).isEqualTo(SIGNED_IN);
	}

	@Test
	void trustsTheClaimedActorOnTheServiceCredentialPath() {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("service", null, List.of()));

		assertThat(CurrentActor.resolve(CLAIMED)).isEqualTo(CLAIMED);
	}

	@Test
	void requiresAnActorWhenNoUserIsSignedIn() {
		assertThatThrownBy(() -> CurrentActor.resolve(null)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void anOptionalActorIsTheSignedInUserWhateverTheRequestNames() {
		signIn(account("uid"));

		assertThat(CurrentActor.resolveOptional(CLAIMED)).isEqualTo(SIGNED_IN);
		assertThat(CurrentActor.resolveOptional(null)).isEqualTo(SIGNED_IN);
	}

	@Test
	void anOptionalActorOnTheServiceCredentialPathIsWhoeverIsNamedOrNobody() {
		signIn("service");

		assertThat(CurrentActor.resolveOptional(CLAIMED)).isEqualTo(CLAIMED);
		assertThat(CurrentActor.resolveOptional(null)).isNull();

		SecurityContextHolder.clearContext();
		assertThat(CurrentActor.resolveOptional(CLAIMED)).isEqualTo(CLAIMED);
	}

	// Before the account exists the bearer filter leaves only the Firebase login as the principal.
	@Test
	void theFirebaseUidComesFromTheLoginItselfDuringOnboarding() {
		signIn(new FirebaseIdentity("uid-new", "yeni@dongu.co", true));

		assertThat(CurrentActor.firebaseUid()).isEqualTo("uid-new");
	}

	@Test
	void theFirebaseUidOfASignedInAccountIsTheOneItsTokenCarried() {
		signIn(account("uid-known"));

		assertThat(CurrentActor.firebaseUid()).isEqualTo("uid-known");
	}

	// The proxy's shared credentials name no Firebase login, so they cannot onboard on anybody's behalf.
	@Test
	void thereIsNoFirebaseUidWithoutAFirebaseSession() {
		assertThatThrownBy(CurrentActor::firebaseUid).isInstanceOf(InvalidRequestException.class);

		signIn("service");
		assertThatThrownBy(CurrentActor::firebaseUid).isInstanceOf(InvalidRequestException.class);

		signIn(account(null));
		assertThatThrownBy(CurrentActor::firebaseUid).isInstanceOf(InvalidRequestException.class);

		signIn(account(" "));
		assertThatThrownBy(CurrentActor::firebaseUid).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aGraveActionTakesTheSignedInAccountAndNeverTheServiceCredentials() {
		assertThatThrownBy(CurrentActor::requireSignedIn).isInstanceOfSatisfying(ResponseStatusException.class,
				exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

		signIn("service");
		assertThatThrownBy(CurrentActor::requireSignedIn).isInstanceOfSatisfying(ResponseStatusException.class,
				exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

		signIn(new FirebaseIdentity("uid-new", "yeni@dongu.co", true));
		assertThatThrownBy(CurrentActor::requireSignedIn).isInstanceOfSatisfying(ResponseStatusException.class,
				exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

		signIn(account("uid"));
		assertThat(CurrentActor.requireSignedIn()).isEqualTo(SIGNED_IN);
	}

	private static AppUserPrincipal account(String firebaseUid) {
		return new AppUserPrincipal(SIGNED_IN, "founder@dongu.co", UserRole.MENTEE, UserAccountStatus.APPROVED,
				firebaseUid);
	}

	private static void signIn(Object principal) {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, List.of()));
	}
}
