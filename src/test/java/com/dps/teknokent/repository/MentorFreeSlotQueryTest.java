package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.Organization;

import jakarta.persistence.EntityManager;

@DataJpaTest
class MentorFreeSlotQueryTest {

	private static final Instant FROM = Instant.parse("2026-11-16T09:00:00Z");
	private static final Instant UNTIL = Instant.parse("2026-11-23T09:00:00Z");

	@Autowired
	private MentorAvailabilitySlotRepository slots;

	@Autowired
	private EntityManager entityManager;

	private Organization organization;

	@BeforeEach
	void setUp() {
		organization = new Organization();
		organization.setName("Kişisel");
		entityManager.persist(organization);
	}

	@Test
	void aMentorWithAnUntakenSlotInTheWindowIsFree() {
		AppUser free = mentor("free");
		slot(free, Instant.parse("2026-11-18T12:00:00Z"));

		assertThat(query(free)).containsExactly(free.getId());
	}

	@Test
	void aSlotHeldByAScheduledMeetingIsTakenButACancelledOneFreesItAgain() {
		AppUser booked = mentor("booked");
		AppUser cancelled = mentor("cancelled");
		Instant at = Instant.parse("2026-11-18T12:00:00Z");
		slot(booked, at);
		meeting(booked, at, MeetingStatus.SCHEDULED);
		slot(cancelled, at);
		meeting(cancelled, at, MeetingStatus.CANCELLED);

		assertThat(query(booked, cancelled)).containsExactly(cancelled.getId());
	}

	@Test
	void theWindowIncludesItsStartAndExcludesItsEnd() {
		AppUser atStart = mentor("start");
		AppUser atEnd = mentor("end");
		AppUser before = mentor("before");
		slot(atStart, FROM);
		slot(atEnd, UNTIL);
		slot(before, FROM.minusSeconds(60));

		assertThat(query(atStart, atEnd, before)).containsExactly(atStart.getId());
	}

	@Test
	void onlyTheAskedMentorsComeBackOnceEach() {
		AppUser asked = mentor("asked");
		AppUser other = mentor("other");
		slot(asked, Instant.parse("2026-11-17T10:00:00Z"));
		slot(asked, Instant.parse("2026-11-19T10:00:00Z"));
		slot(other, Instant.parse("2026-11-17T10:00:00Z"));

		assertThat(query(asked)).containsExactly(asked.getId());
	}

	private List<UUID> query(AppUser... mentors) {
		entityManager.flush();
		entityManager.clear();
		return slots.findMentorIdsWithFreeSlotBetween(List.of(mentors).stream().map(AppUser::getId).toList(), FROM,
				UNTIL, MeetingStatus.SCHEDULED);
	}

	private AppUser mentor(String name) {
		AppUser mentor = new AppUser();
		mentor.setEmail(name + "-free-slot@odtu-dev.invalid");
		mentor.setFullName(name);
		mentor.setRole(UserRole.MENTOR);
		entityManager.persist(mentor);
		return mentor;
	}

	private void slot(AppUser mentor, Instant startsAt) {
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setMentor(mentor);
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(60);
		slot.setCreatedAt(Instant.parse("2026-11-01T00:00:00Z"));
		entityManager.persist(slot);
	}

	private void meeting(AppUser mentor, Instant at, MeetingStatus status) {
		Meeting meeting = new Meeting();
		meeting.setOrganization(organization);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(60);
		meeting.setStatus(status);
		meeting.setTranscribeRequested(false);
		entityManager.persist(meeting);
	}
}
