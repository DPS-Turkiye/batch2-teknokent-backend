package com.dps.teknokent.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.PeerReportStatus;
import com.dps.teknokent.entity.PeerDailyReport;

public interface PeerDailyReportRepository extends JpaRepository<PeerDailyReport, UUID> {

	Optional<PeerDailyReport> findByMentorIdAndReportDate(UUID mentorId, LocalDate reportDate);

	List<PeerDailyReport> findByMentorIdAndReportDateBetween(UUID mentorId, LocalDate from, LocalDate to);

	List<PeerDailyReport> findByProgramIdInAndStatusInOrderBySubmittedAtAsc(Collection<UUID> programIds,
			Collection<PeerReportStatus> statuses);

	List<PeerDailyReport> findByStatusInOrderBySubmittedAtAsc(Collection<PeerReportStatus> statuses);
}
