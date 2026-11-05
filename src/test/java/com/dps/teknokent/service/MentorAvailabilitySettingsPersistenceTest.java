package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.requests.MentorAvailabilityExceptionRequest;
import com.dps.teknokent.dto.requests.UpdateMentorAvailabilitySettingsRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.MentorAvailabilityExceptionRepository;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;

import jakarta.persistence.EntityManager;

/**
 * Runs the settings replace against a real persistence context: the mocked service test
 * can't see Hibernate's insert-before-delete flush order or the unique constraints.
 */
@DataJpaTest
@Import({ MentorProfileService.class, DomainLookupService.class, MenteeProgramService.class })
class MentorAvailabilitySettingsPersistenceTest {

	@Autowired
	private MentorProfileService mentorProfileService;

	@Autowired
	private MentorAvailabilityExceptionRepository exceptionRepository;

	@Autowired
	private MentorAvailabilityRuleRepository ruleRepository;

	@Autowired
	private MentorAvailabilitySlotRepository slotRepository;

	@Autowired
	private EntityManager entityManager;

	private AppUser mentor;

	@BeforeEach
	void setUp() {
		mentor = new AppUser();
		mentor.setEmail("availability-mentor@tekno.com");
		mentor.setFullName("Availability Mentor");
		mentor.setRole(UserRole.MENTOR);
		entityManager.persist(mentor);

		MentorProfile profile = new MentorProfile();
		profile.setUser(mentor);
		profile.setInitials("AM");
		profile.setProfessionalTitle("Mentor");
		profile.setCompany("Teknokent");
		profile.setAvailabilityLabel("Mentorluğa açık");
		profile.setResponseTimeLabel("~48 saat");
		profile.setMeetingMode("Online");
		profile.setCommunicationPreference("Mesajlaşma");
		profile.setMentoringStyle("İşbirlikçi");
		profile.setFitLabel("Yüksek");
		profile.setExperienceLevel("10 yıl");
		profile.setSectorExperience("SaaS");
		profile.setStartupStages("MVP");
		entityManager.persist(profile);
		entityManager.flush();
	}

	@Test
	void resavingUnchangedRangesAndDatesReplacesThemInsteadOfColliding() {
		List<CreateMentorAvailabilityRuleRequest> rules = List.of(new CreateMentorAvailabilityRuleRequest(
				DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), 60));
		List<MentorAvailabilityExceptionRequest> dates = List.of(new MentorAvailabilityExceptionRequest(
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "İzin"));

		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(rules, dates, 3, 45, 15));
		entityManager.flush();
		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(rules, dates, null, 60, 15));
		entityManager.flush();
		entityManager.clear();

		assertThat(exceptionRepository.findByMentorId(mentor.getId()))
				.singleElement()
				.satisfies(saved -> assertThat(saved.getStartsOn()).isEqualTo(LocalDate.of(2026, 10, 1)));
		assertThat(ruleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentor.getId())).hasSize(1);
		MentorProfile saved = entityManager.find(MentorProfile.class, mentor.getId());
		assertThat(saved.getMenteeCapacity()).isEqualTo(3);
		assertThat(saved.getDefaultMeetingDurationMinutes()).isEqualTo(60);
	}

	@Test
	void resavingKeepsABookedSlotAndUnlinksItFromTheReplacedRule() {
		List<CreateMentorAvailabilityRuleRequest> rules = List.of(new CreateMentorAvailabilityRuleRequest(
				DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), 60));
		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(rules, List.of(), null, 60, 15));
		MentorAvailabilitySlot booked = slotRepository
				.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(mentor.getId(), Instant.now()).getFirst();
		Organization startup = new Organization();
		startup.setName("Startup");
		entityManager.persist(startup);
		Meeting meeting = new Meeting();
		meeting.setOrganization(startup);
		meeting.setMentor(mentor);
		meeting.setScheduledAt(booked.getStartsAt());
		meeting.setDurationMinutes(60);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		entityManager.persist(meeting);
		entityManager.flush();

		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(rules, List.of(), null, 60, 15));
		entityManager.flush();
		entityManager.clear();

		MentorAvailabilitySlot kept = entityManager.find(MentorAvailabilitySlot.class, booked.getId());
		assertThat(kept).isNotNull();
		assertThat(kept.getRule()).isNull();
	}

	@Test
	void resavingOnlyUnchangedDatesReplacesThem() {
		List<MentorAvailabilityExceptionRequest> dates = List.of(new MentorAvailabilityExceptionRequest(
				LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2), null));

		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(List.of(), dates, null, 45, 15));
		entityManager.flush();
		mentorProfileService.updateAvailabilitySettings(mentor.getId(),
				new UpdateMentorAvailabilitySettingsRequest(List.of(), dates, null, 45, 30));
		entityManager.flush();

		assertThat(exceptionRepository.findByMentorId(mentor.getId())).hasSize(1);
	}
}
