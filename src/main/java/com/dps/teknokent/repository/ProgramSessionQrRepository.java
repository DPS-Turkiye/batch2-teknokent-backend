package com.dps.teknokent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.entity.ProgramSessionQr;

public interface ProgramSessionQrRepository extends JpaRepository<ProgramSessionQr, UUID> {

	Optional<ProgramSessionQr> findByToken(String token);

	Optional<ProgramSessionQr> findByProgramIdAndWeekNumberAndSessionKind(UUID programId, int weekNumber,
			AttendanceSessionKind sessionKind);

	List<ProgramSessionQr> findByProgramId(UUID programId);
}
