package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.entity.Bottleneck;

public interface BottleneckRepository extends JpaRepository<Bottleneck, UUID> {

	List<Bottleneck> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	long countByStatusIn(Collection<BottleneckStatus> statuses);
	long countByOrganizationIdAndStatusIn(UUID organizationId, Collection<BottleneckStatus> statuses);

	long countBySeverityInAndStatusIn(
			Collection<BottleneckSeverity> severities,
			Collection<BottleneckStatus> statuses);

	long countByOrganizationIdAndSeverityInAndStatusIn(
			UUID organizationId,
			Collection<BottleneckSeverity> severities,
			Collection<BottleneckStatus> statuses);

	List<Bottleneck> findByOrganizationIdInAndStatusIn(
			Collection<UUID> organizationIds,
			Collection<BottleneckStatus> statuses);
}
