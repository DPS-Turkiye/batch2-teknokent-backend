package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OrganizationPendingMember;

public interface OrganizationPendingMemberRepository extends JpaRepository<OrganizationPendingMember, UUID> {

	List<OrganizationPendingMember> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

	Optional<OrganizationPendingMember> findByIdAndOrganizationId(UUID id, UUID organizationId);

	void deleteByOrganizationIdAndEmailIgnoreCase(UUID organizationId, String email);
}
