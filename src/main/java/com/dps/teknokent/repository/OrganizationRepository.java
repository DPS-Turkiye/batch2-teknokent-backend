package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.LockModeType;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
	Optional<Organization> findByInviteCode(String inviteCode);

	/** The row locked until the transaction ends, so two admins cannot move the same team at once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from Organization o where o.id = :id")
	Optional<Organization> findByIdForUpdate(@Param("id") UUID id);

	Optional<Organization> findByFounderId(UUID founderId);

	boolean existsByInviteCode(String inviteCode);

	List<Organization> findByIdInAndApprovalStatusOrderByCreatedAtAsc(Collection<UUID> ids, UserAccountStatus status);

	@Query("""
			select count(distinct o.id) from Organization o, ProgramStartup ps
			where ps.organizationId = o.id and ps.programId = :programId and o.approvalStatus = :status
			""")
	long countInProgramByApprovalStatus(@Param("programId") UUID programId, @Param("status") UserAccountStatus status);
}
