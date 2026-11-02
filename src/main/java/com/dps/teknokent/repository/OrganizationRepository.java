package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
	Optional<Organization> findByInviteCode(String inviteCode);

	Optional<Organization> findByFounderId(UUID founderId);

	boolean existsByInviteCode(String inviteCode);

	List<Organization> findByIdInAndApprovalStatusOrderByCreatedAtAsc(Collection<UUID> ids, UserAccountStatus status);
}
