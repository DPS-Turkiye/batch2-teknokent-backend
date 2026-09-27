package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.ProgramAnnouncement;

public interface ProgramAnnouncementRepository extends JpaRepository<ProgramAnnouncement, UUID> {

	List<ProgramAnnouncement> findByProgramIdOrderByCreatedAtDesc(UUID programId);

	List<ProgramAnnouncement> findByProgramIdInOrderByCreatedAtDesc(Collection<UUID> programIds);
}
