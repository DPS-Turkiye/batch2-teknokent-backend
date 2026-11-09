package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursRequestMessage;

public interface OfficeHoursRequestMessageRepository extends JpaRepository<OfficeHoursRequestMessage, UUID> {

	List<OfficeHoursRequestMessage> findByRequestIdInOrderByCreatedAtAsc(Collection<UUID> requestIds);
}
