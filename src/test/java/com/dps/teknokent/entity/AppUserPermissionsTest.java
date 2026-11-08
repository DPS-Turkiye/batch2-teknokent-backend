package com.dps.teknokent.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PermissionStatus;
import com.dps.teknokent.domain.UserRole;

class AppUserPermissionsTest {

	@Test
	void aNewAccountGetsItsRoleAsItsOnlyPermission() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTEE);

		assertThat(user.activePermissions()).containsExactly(UserRole.MENTEE);
		assertThat(grants(user)).singleElement().satisfies(grant -> {
			assertThat(grant.getSource()).isEqualTo(PermissionSource.REGISTRATION);
			assertThat(grant.getStatus()).isEqualTo(PermissionStatus.ACTIVE);
			assertThat(grant.getUser()).isSameAs(user);
		});
	}

	// Until dual role lands, changing the role must not leave the old permission behind.
	@Test
	void changingTheRoleRevokesTheOldPermissionInPlace() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTEE);
		user.setId(UUID.randomUUID());

		user.setRole(UserRole.COORDINATOR);

		assertThat(user.activePermissions()).containsExactly(UserRole.COORDINATOR);
		assertThat(user.has(UserRole.MENTEE)).isFalse();
		assertThat(grants(user)).hasSize(2);
		AppUserPermission old = grants(user).getFirst();
		assertThat(old.getStatus()).isEqualTo(PermissionStatus.REVOKED);
		assertThat(old.getRevokedAt()).isNotNull();
		assertThat(grants(user).get(1).getSource()).isEqualTo(PermissionSource.ADMIN);
	}

	// One row per permission: setting the same role again or returning to an old one reuses the row.
	@Test
	void settingARoleAgainReusesItsRow() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTEE);
		user.setRole(UserRole.MENTEE);
		user.setRole(UserRole.MENTOR);
		user.setRole(UserRole.MENTEE);

		assertThat(grants(user)).hasSize(2);
		assertThat(user.activePermissions()).containsExactly(UserRole.MENTEE);
		assertThat(grants(user).getFirst().getRevokedAt()).isNull();
	}

	// A seed migration written after the grant table may insert an account without its row.
	@Test
	void thePrimaryRoleCountsEvenWithoutARow() {
		AppUser user = new AppUser();
		ReflectionTestUtils.setField(user, "role", UserRole.MENTOR);

		assertThat(user.has(UserRole.MENTOR)).isTrue();
		assertThat(user.has(UserRole.ADMIN)).isFalse();
	}

	@Test
	void anExpiredGrantIsNotActive() {
		AppUser user = new AppUser();
		ReflectionTestUtils.setField(user, "role", UserRole.MENTEE);
		AppUserPermission mentor = new AppUserPermission(user, UserRole.MENTOR, PermissionSource.BADGE,
				Instant.now().minusSeconds(60));
		mentor.setExpiresAt(Instant.now().minusSeconds(1));
		grants(user).add(mentor);

		assertThat(user.activePermissions()).containsExactly(UserRole.MENTEE);
	}

	@Test
	void anActiveSecondGrantIsHeldAlongsideThePrimaryRole() {
		AppUser user = new AppUser();
		ReflectionTestUtils.setField(user, "role", UserRole.MENTEE);
		grants(user).add(new AppUserPermission(user, UserRole.MENTOR, PermissionSource.BADGE, Instant.now()));

		assertThat(user.activePermissions()).containsExactly(UserRole.MENTOR, UserRole.MENTEE);
	}

	@Test
	void anExplicitlyRevokedPrimaryPermissionDoesNotFallBackToTheRole() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTOR);
		grants(user).getFirst().revoke(Instant.now());

		assertThat(user.has(UserRole.MENTOR)).isFalse();
	}

	@Test
	void anExpiredPrimaryPermissionDoesNotFallBackToTheRole() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTOR);
		grants(user).getFirst().setExpiresAt(Instant.now().minusSeconds(1));

		assertThat(user.activePermissions()).isEmpty();
	}

	// A research assistant is a student and an academician at once: granting adds, it never replaces.
	@Test
	void grantingAddsAPermissionNextToTheOthers() {
		AppUser user = new AppUser();
		user.setPrimaryRole(UserRole.ACADEMICIAN);
		Instant now = Instant.now();

		user.grant(UserRole.ACADEMICIAN, PermissionSource.INSTITUTION, now);
		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, now);
		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, now);

		assertThat(user.activePermissions()).containsExactlyInAnyOrder(UserRole.ACADEMICIAN, UserRole.MENTEE);
		assertThat(grants(user)).hasSize(2);
		assertThat(user.getRole()).isEqualTo(UserRole.ACADEMICIAN);
	}

	// A student who graduates loses what the institution gave, but keeps what sign-up or a badge gave.
	@Test
	void withdrawingASourceKeepsWhatOtherSourcesGave() {
		AppUser user = new AppUser();
		user.setRole(UserRole.MENTEE);
		Instant now = Instant.now();
		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, now);
		user.grant(UserRole.ACADEMICIAN, PermissionSource.INSTITUTION, now);
		user.grant(UserRole.MENTOR, PermissionSource.BADGE, now);

		user.withdraw(PermissionSource.INSTITUTION, java.util.Set.of(), now);

		assertThat(user.activePermissions()).containsExactlyInAnyOrder(UserRole.MENTEE, UserRole.MENTOR);
		assertThat(grants(user)).filteredOn(grant -> grant.getPermission() == UserRole.ACADEMICIAN)
				.singleElement()
				.satisfies(grant -> assertThat(grant.getStatus()).isEqualTo(PermissionStatus.REVOKED));
	}

	@Test
	void grantingBringsBackALapsedPermissionUnderTheNewSource() {
		AppUser user = new AppUser();
		user.setPrimaryRole(UserRole.MENTEE);
		Instant now = Instant.now();
		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, now);
		user.withdraw(PermissionSource.INSTITUTION, java.util.Set.of(), now);
		assertThat(user.has(UserRole.MENTEE)).isFalse();

		user.grant(UserRole.MENTEE, PermissionSource.INSTITUTION, now);

		assertThat(user.has(UserRole.MENTEE)).isTrue();
		assertThat(grants(user)).hasSize(1);
	}

	@SuppressWarnings("unchecked")
	private static List<AppUserPermission> grants(AppUser user) {
		return (List<AppUserPermission>) ReflectionTestUtils.getField(user, "permissions");
	}
}
