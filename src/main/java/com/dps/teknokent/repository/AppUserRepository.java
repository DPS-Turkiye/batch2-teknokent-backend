package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	List<AppUser> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);

	List<AppUser> findByOrganizationIdInOrderByFullNameAsc(Collection<UUID> organizationIds);

	boolean existsByEmailIgnoreCase(String email);

	Optional<AppUser> findByEmailIgnoreCase(String email);

	Optional<AppUser> findByFirebaseUid(String firebaseUid);

	List<AppUser> findByRoleAndStatusOrderByFullNameAsc(UserRole role, UserAccountStatus status);

	// One column only, so a profile edit saved at the same moment is not overwritten.
	@Transactional
	@Modifying
	@Query("update AppUser u set u.signInsRevokedAt = :at where u.id = :id")
	int recordSignInsRevokedAt(@Param("id") UUID id, @Param("at") Instant at);
}
