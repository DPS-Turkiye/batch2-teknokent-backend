package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.entity.ActionItem;

public interface ActionItemRepository extends JpaRepository<ActionItem, UUID> {

	@EntityGraph(attributePaths = "createdBy")
	List<ActionItem> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	List<ActionItem> findByMentorshipRequestIdInAndMentorshipRequestOrganizationIdOrderByCreatedAtDesc(
			Collection<UUID> mentorshipRequestIds, UUID organizationId);

	List<ActionItem> findByMentorshipRequestIdInAndMentorshipRequestMentorIdOrderByCreatedAtDesc(
			Collection<UUID> mentorshipRequestIds, UUID mentorId);

	Optional<ActionItem> findByIdAndOrganizationId(UUID id, UUID organizationId);

	List<ActionItem> findByOrganizationIdAndOwnerIdAndStatusIn(
			UUID organizationId, UUID ownerId, Collection<ActionItemStatus> statuses);

	List<ActionItem> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<ActionItemStatus> statuses);

	List<ActionItem> findByStatusInAndDeadlineRemindedAtIsNullAndDueDateBetween(
			Collection<ActionItemStatus> statuses, LocalDate from, LocalDate until);

	long countByOrganizationIdAndStatusNotInAndDueDateBefore(
			UUID organizationId, Collection<ActionItemStatus> statuses, LocalDate threshold);

	List<ActionItem> findByOrganizationIdInAndStatusNotInAndDueDateBefore(
			Collection<UUID> organizationIds,
			Collection<ActionItemStatus> statuses,
			LocalDate threshold);

	List<ActionItem> findByOrganizationIdInAndStatusNotIn(
			Collection<UUID> organizationIds,
			Collection<ActionItemStatus> statuses);

	List<ActionItem> findByOrganizationIdInAndStatusNotInAndDueDateBetween(
			Collection<UUID> organizationIds,
			Collection<ActionItemStatus> statuses,
			LocalDate from,
			LocalDate to);

	@EntityGraph(attributePaths = { "organization", "owner" })
	List<ActionItem> findByProgramAssignmentIdIn(Collection<UUID> programAssignmentIds);

	/** The program Ödevler the team already holds a copy of. */
	@Query("select item.programAssignment.id from ActionItem item"
			+ " where item.organization.id = :organizationId and item.programAssignment is not null")
	List<UUID> findProgramAssignmentIdsOfOrganization(@Param("organizationId") UUID organizationId);
}
