package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select user from AppUser user where user.id = :actorId")
	Optional<AppUser> findByIdForUpdate(@Param("actorId") UUID actorId);
	List<AppUser> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);

	boolean existsByEmailIgnoreCase(String email);

	Optional<AppUser> findByEmailIgnoreCase(String email);

	Optional<AppUser> findByFirebaseUid(String firebaseUid);

	List<AppUser> findByRoleAndStatusOrderByFullNameAsc(UserRole role, UserAccountStatus status);
}
