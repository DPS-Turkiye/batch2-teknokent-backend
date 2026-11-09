package com.dps.teknokent.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PermissionStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.repository.AppUserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
class AppUserPermissionLookupTest {

	@Autowired
	private EntityManager entityManager;
	@Autowired
	private AppUserRepository repository;

	@Test
	void listsASecondaryGrantAndRejectsRevokedPendingAndExpiredGrants() {
		AppUser active = user("Active", PermissionStatus.ACTIVE, null);
		user("Revoked", PermissionStatus.REVOKED, null);
		user("Pending", PermissionStatus.PENDING, null);
		user("Expired", PermissionStatus.ACTIVE, Instant.now().minusSeconds(60));
		entityManager.flush();
		entityManager.clear();

		assertThat(repository.findByPermissionAndStatusOrderByFullNameAsc(UserRole.COORDINATOR,
				UserAccountStatus.APPROVED)).extracting(AppUser::getId).containsExactly(active.getId());
	}

	@Test
	void programFiltersUseSecondaryGrantsToo() {
		AppUser active = user("Active", PermissionStatus.ACTIVE, null);
		UUID programId = UUID.randomUUID();
		active.setProgramId(programId);
		user("Other program", PermissionStatus.ACTIVE, null).setProgramId(UUID.randomUUID());
		entityManager.flush();
		entityManager.clear();

		assertThat(repository.findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(programId,
				UserRole.COORDINATOR, UserAccountStatus.APPROVED)).extracting(AppUser::getId)
				.containsExactly(active.getId());
		assertThat(repository.findByProgramIdAndPermissionAndStatusAndOrganizationIsNullOrderByFullNameAsc(
				programId, UserRole.COORDINATOR, UserAccountStatus.APPROVED)).extracting(AppUser::getId)
				.containsExactly(active.getId());
	}

	@Test
	void anExplicitRevocationOverridesPrimaryRoleInDatabaseFilters() {
		AppUser user = new AppUser();
		user.setEmail("revoked@example.test");
		user.setFullName("Revoked primary");
		user.setRole(UserRole.COORDINATOR);
		entityManager.persist(user);
		entityManager.flush();
		entityManager.createQuery("update AppUserPermission p set p.status = :status where p.user = :user")
				.setParameter("status", PermissionStatus.REVOKED).setParameter("user", user).executeUpdate();
		entityManager.clear();

		assertThat(repository.findByPermissionAndStatusOrderByFullNameAsc(UserRole.COORDINATOR,
				UserAccountStatus.APPROVED)).isEmpty();
	}

	private AppUser user(String name, PermissionStatus status, Instant expiresAt) {
		AppUser user = new AppUser();
		user.setEmail(UUID.randomUUID() + "@example.test");
		user.setFullName(name);
		user.setRole(UserRole.MENTEE);
		entityManager.persist(user);
		AppUserPermission grant = new AppUserPermission(user, UserRole.COORDINATOR,
				PermissionSource.ADMIN, Instant.now());
		grant.setStatus(status);
		grant.setExpiresAt(expiresAt);
		entityManager.persist(grant);
		return user;
	}
}
