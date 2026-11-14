package com.dps.teknokent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.util.Texts;

class AdminAccessTest {

	@Test
	void letsAnApprovedAdminThrough() {
		AppUser admin = user(UserRole.ADMIN, UserAccountStatus.APPROVED);

		assertThat(AdminAccess.requireAdmin(admin)).isSameAs(admin);
	}

	@Test
	void refusesACoordinatorAMentorAndNobodyWithForbidden() {
		for (AppUser caller : new AppUser[] { user(UserRole.COORDINATOR, UserAccountStatus.APPROVED),
				user(UserRole.MENTOR, UserAccountStatus.APPROVED), null }) {
			assertThatThrownBy(() -> AdminAccess.requireAdmin(caller))
					.isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
						assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
						assertThat(refused.getReason()).isEqualTo(Texts.forRequest("error.adminOnly"));
					});
		}
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
	void refusesAnAdminWhoseAccountIsNotApproved(UserAccountStatus status) {
		assertThatThrownBy(() -> AdminAccess.requireAdmin(user(UserRole.ADMIN, status)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void speaksWithTheServicesOwnTextWhenGivenOne() {
		assertThatThrownBy(() -> AdminAccess.requireAdmin(user(UserRole.MENTEE, UserAccountStatus.APPROVED),
				"feedback.adminOnly"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						refused -> assertThat(refused.getReason()).isEqualTo(Texts.forRequest("feedback.adminOnly")));
	}

	private static AppUser user(UserRole role, UserAccountStatus status) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Demo " + role);
		user.setRole(role);
		user.setStatus(status);
		return user;
	}
}
