package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.PeerWeeklyReport;

public interface PeerWeeklyReportRepository extends JpaRepository<PeerWeeklyReport, UUID> {

	Optional<PeerWeeklyReport> findByMentorIdAndWeekStart(UUID mentorId, LocalDate weekStart);
}
