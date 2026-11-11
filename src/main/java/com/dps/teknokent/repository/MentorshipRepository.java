package com.dps.teknokent.repository;

import java.util.Collection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.entity.Mentorship;

public interface MentorshipRepository extends JpaRepository<Mentorship, UUID> {

	List<Mentorship> findAllByOrderByCreatedAtDesc();

	boolean existsByMentorIdAndOrganizationId(UUID mentorId, UUID organizationId);

	boolean existsByMentorIdAndOrganizationIdAndStatusIn(
			UUID mentorId,
			UUID organizationId,
			Collection<MentorshipStatus> statuses);

	long countByOrganizationIdAndStatus(UUID organizationId, MentorshipStatus status);

	@Query("""
			SELECT m FROM Mentorship m
			WHERE m.mentor.id = :mentorId
			AND m.status = :status
			AND m.startDate <= :today
			AND (m.endDate IS NULL OR m.endDate >= :today)
			ORDER BY m.createdAt DESC
			""")
	List<Mentorship> findActiveForMentorOn(
			@Param("mentorId") UUID mentorId,
			@Param("status") MentorshipStatus status,
			@Param("today") LocalDate today);

	@Query("""
			SELECT m FROM Mentorship m
			WHERE m.organization.id = :organizationId
			AND m.status = :status
			AND m.startDate <= :today
			AND (m.endDate IS NULL OR m.endDate >= :today)
			ORDER BY m.createdAt DESC
			""")
	List<Mentorship> findActiveForOrganizationOn(
			@Param("organizationId") UUID organizationId,
			@Param("status") MentorshipStatus status,
			@Param("today") LocalDate today);

	@Query("""
			SELECT m FROM Mentorship m
			WHERE m.mentor.id = :mentorId
			AND m.status = :status
			AND m.startDate <= :weekEnd
			AND (m.endDate IS NULL OR m.endDate >= :weekStart)
			ORDER BY m.organization.name ASC
			""")
	List<Mentorship> findOverlappingWeekForMentor(
			@Param("mentorId") UUID mentorId,
			@Param("status") MentorshipStatus status,
			@Param("weekStart") LocalDate weekStart,
			@Param("weekEnd") LocalDate weekEnd);

	@Query("""
			SELECT m.mentor.id AS mentorId, COUNT(DISTINCT m.organization.id) AS total
			FROM Mentorship m
			WHERE m.mentor.id IN :mentorIds AND m.status IN :statuses
			GROUP BY m.mentor.id
			""")
	List<MentorCount> countOrganizationsByMentor(
			@Param("mentorIds") Collection<UUID> mentorIds,
			@Param("statuses") Collection<MentorshipStatus> statuses);

	@Query("""
			SELECT m.mentor.id AS mentorId, COUNT(m) AS total
			FROM Mentorship m
			WHERE m.mentor.id IN :mentorIds AND m.status = :status
			GROUP BY m.mentor.id
			""")
	List<MentorCount> countMentorshipsByMentor(
			@Param("mentorIds") Collection<UUID> mentorIds,
			@Param("status") MentorshipStatus status);

	List<Mentorship> findByOrganizationIdIn(Collection<UUID> organizationIds);

	long countByMentorIdAndStatusIn(UUID mentorId, Collection<MentorshipStatus> statuses);
}
