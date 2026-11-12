package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.OfficeHoursSettings;

public interface OfficeHoursSettingsRepository extends JpaRepository<OfficeHoursSettings, UUID> {

	/** The academicians who chose to show up in the Menton students' list. */
	List<OfficeHoursSettings> findByListedTrue();
}
