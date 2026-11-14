package com.dps.teknokent.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dps.teknokent.entity.OfficeHoursSettings;

public interface OfficeHoursSettingsRepository extends JpaRepository<OfficeHoursSettings, UUID> {

	/** The academicians who chose to show up in the Menton students' list. */
	List<OfficeHoursSettings> findByListedTrue();

	/**
	 * Adds the academic's settings row unless it is there, so two first saves at once (profile and hours) both
	 * find it instead of one failing on the primary key.
	 */
	@Modifying
	@Query(value = """
			INSERT INTO office_hours_settings (instructor_id, default_meeting_duration_minutes, meeting_buffer_minutes,
			    listed, updated_at)
			VALUES (:instructorId, :meetingMinutes, :bufferMinutes, FALSE, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("instructorId") UUID instructorId, @Param("meetingMinutes") int meetingMinutes,
			@Param("bufferMinutes") int bufferMinutes, @Param("now") Instant now);
}
