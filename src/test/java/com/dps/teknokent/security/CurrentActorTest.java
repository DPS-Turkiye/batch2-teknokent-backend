package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.domain.UserAccountStatus;
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
}
