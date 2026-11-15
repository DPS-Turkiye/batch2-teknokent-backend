package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.entity.OfficeHoursMeeting;

@DataJpaTest
class OfficeHoursMeetingPersistenceTest {

	private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");

	@Autowired
	private OfficeHoursMeetingRepository meetings;

	private OfficeHoursMeeting booked() {
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setId(UUID.randomUUID());
		meeting.setRequestId(UUID.randomUUID());
		meeting.setInstructorId(UUID.randomUUID());
		meeting.setStudentId(UUID.randomUUID());
		meeting.setScheduledAt(NOW.plusSeconds(20 * 3600));
		meeting.setDurationMinutes(30);
		meeting.setCreatedAt(NOW.minusSeconds(3 * 24 * 3600));
		return meetings.saveAndFlush(meeting);
	}

	@Test
	void aLiveMeetingsReminderIsClaimedOncePerSide() {
		OfficeHoursMeeting meeting = booked();

		assertThat(meetings.claimStudentReminder(meeting.getId(), NOW)).isEqualTo(1);
		assertThat(meetings.claimStudentReminder(meeting.getId(), NOW)).isZero();
		assertThat(meetings.claimInstructorReminder(meeting.getId(), NOW)).isEqualTo(1);
	}

	@Test
	void aMeetingCancelledAfterTheDueListWasReadCannotBeClaimedSoGetsNoReminder() {
		OfficeHoursMeeting meeting = booked();
		// The reminder job has read the meeting as due; then one side cancels it before the job claims it.
		assertThat(meetings.findReminderDue(NOW, NOW.plusSeconds(24 * 3600))).extracting(OfficeHoursMeeting::getId)
				.contains(meeting.getId());
		meetings.cancelIfLive(meeting.getId(), NOW, meeting.getStudentId(), null);

		assertThat(meetings.claimStudentReminder(meeting.getId(), NOW)).isZero();
		assertThat(meetings.claimInstructorReminder(meeting.getId(), NOW)).isZero();
	}
}
