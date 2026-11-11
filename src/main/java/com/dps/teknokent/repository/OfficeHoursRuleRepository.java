package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursRule;

public interface OfficeHoursRuleRepository extends JpaRepository<OfficeHoursRule, UUID> {

	List<OfficeHoursRule> findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(UUID instructorId);
}
