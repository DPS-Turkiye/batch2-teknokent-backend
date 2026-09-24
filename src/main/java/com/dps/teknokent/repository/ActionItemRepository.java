package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.entity.ActionItem;

public interface ActionItemRepository extends JpaRepository<ActionItem, UUID> {

	@EntityGraph(attributePaths = "createdBy")
	List<ActionItem> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<ActionItem> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByStatusNotInAndDueDateBefore(Collection<ActionItemStatus> statuses, LocalDate threshold);
	long countByOrganizationIdAndStatusNotInAndDueDateBefore(
			UUID organizationId, Collection<ActionItemStatus> statuses, LocalDate threshold);

	List<ActionItem> findByOrganizationIdInAndStatusNotInAndDueDateBefore(
			Collection<UUID> organizationIds,
			Collection<ActionItemStatus> statuses,
			LocalDate threshold);
}
