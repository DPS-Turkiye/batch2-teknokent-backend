package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

import jakarta.persistence.LockModeType;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	List<AppUser> findByOrganizationIdOrderByFullNameAsc(UUID organizationId);

	List<AppUser> findByOrganizationIdInOrderByFullNameAsc(Collection<UUID> organizationIds);

	/** Lowercased addresses from {@code emails} (already lowercase) that belong to an account. */
	@Query("select lower(u.email) from AppUser u where lower(u.email) in :emails")
	List<String> findRegisteredEmails(@Param("emails") Collection<String> emails);

	boolean existsByEmailIgnoreCase(String email);

	Optional<AppUser> findByEmailIgnoreCase(String email);

	Optional<AppUser> findByFirebaseUid(String firebaseUid);

	/** The row locked until the transaction ends, so two admins cannot delete the same account at once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from AppUser u where u.id = :id")
	Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

	List<AppUser> findByStatusNot(UserAccountStatus status);

	/** Match effective grants, using role only for legacy users without that permission's row. */
	String HAS_PERMISSION = "((u.role = :role and not exists (select p.id from AppUserPermission p "
			+ "where p.user = u and p.permission = :role)) or exists (select p.id from AppUserPermission p "
			+ "where p.user = u and p.permission = :role "
			+ "and p.status = com.dps.teknokent.domain.PermissionStatus.ACTIVE "
			+ "and (p.expiresAt is null or p.expiresAt > CURRENT_TIMESTAMP)))";

	@Query("select u from AppUser u where " + HAS_PERMISSION + " and u.status = :status order by u.fullName asc")
	List<AppUser> findByPermissionAndStatusOrderByFullNameAsc(@Param("role") UserRole role,
			@Param("status") UserAccountStatus status);

	/**
	 * Accounts holding {@code role} whose email starts with {@code prefix}: lowercase, with LIKE's {@code %},
	 * {@code _} and backslash already escaped by the caller.
	 */
	@Query("select u from AppUser u where " + HAS_PERMISSION + " and u.status = :status "
			+ "and lower(u.email) like concat(:prefix, '%') escape '\\' order by lower(u.email) asc")
	List<AppUser> findByPermissionAndStatusAndEmailPrefix(@Param("role") UserRole role,
			@Param("status") UserAccountStatus status, @Param("prefix") String prefix, Pageable page);

	/** The program's students who have a personal team, for counting their peer sessions with the program. */
	@Query("select u from AppUser u where u.programId = :programId and u.organization.personal = true")
	List<AppUser> findPersonalTeamStudentsOfProgram(@Param("programId") UUID programId);

	@Query("select u from AppUser u where " + HAS_PERMISSION
			+ " and u.programId = :programId and u.status = :status order by u.createdAt asc")
	List<AppUser> findByProgramIdAndPermissionAndStatusOrderByCreatedAtAsc(@Param("programId") UUID programId,
			@Param("role") UserRole role, @Param("status") UserAccountStatus status);

	@Query("select count(u) from AppUser u where " + HAS_PERMISSION
			+ " and u.programId = :programId and u.status = :status")
	long countByProgramIdAndPermissionAndStatus(@Param("programId") UUID programId,
			@Param("role") UserRole role, @Param("status") UserAccountStatus status);

	@Query("select u from AppUser u where " + HAS_PERMISSION
			+ " and u.programId = :programId and u.status = :status and u.organization is null order by u.fullName asc")
	List<AppUser> findByProgramIdAndPermissionAndStatusAndOrganizationIsNullOrderByFullNameAsc(
			@Param("programId") UUID programId, @Param("role") UserRole role, @Param("status") UserAccountStatus status);

	// One column only, so a profile edit saved at the same moment is not overwritten.
	@Transactional
	@Modifying
	@Query("update AppUser u set u.signInsRevokedAt = :at where u.id = :id")
	int recordSignInsRevokedAt(@Param("id") UUID id, @Param("at") Instant at);
}
