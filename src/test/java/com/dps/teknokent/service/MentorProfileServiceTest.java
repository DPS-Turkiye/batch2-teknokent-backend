package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorProfileRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorProfileServiceTest {

	@Mock
	private MentorProfileRepository mentorProfileRepository;
	@Mock
	private MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private DomainLookupService domainLookupService;

	private MentorProfileService mentorProfileService;

	private final UUID mentorId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		mentorProfileService = new MentorProfileService(
				mentorProfileRepository, mentorAvailabilitySlotRepository, meetingRepository, domainLookupService);
	}

	private AppUser mentor() {
		AppUser user = new AppUser();
		user.setId(mentorId);
		user.setRole(UserRole.MENTOR);
		return user;
	}

	@Test
	void createAvailabilitySlotRejectsPastStartTime() {
		lenient().when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		CreateMentorAvailabilitySlotRequest request =
				new CreateMentorAvailabilitySlotRequest(Instant.now().minus(1, ChronoUnit.DAYS), 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotRejectsNonMentorUser() {
		AppUser mentee = new AppUser();
		mentee.setId(mentorId);
		mentee.setRole(UserRole.MENTEE);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentee);
		CreateMentorAvailabilitySlotRequest request =
				new CreateMentorAvailabilitySlotRequest(Instant.now().plus(1, ChronoUnit.DAYS), 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotRejectsDuplicateStartTime() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentorId, startsAt)).thenReturn(true);
		CreateMentorAvailabilitySlotRequest request = new CreateMentorAvailabilitySlotRequest(startsAt, 60);

		assertThatThrownBy(() -> mentorProfileService.createAvailabilitySlot(mentorId, request))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void createAvailabilitySlotDefaultsDurationTo60Minutes() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		when(mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentorId, startsAt)).thenReturn(false);
		when(mentorAvailabilitySlotRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			MentorAvailabilitySlot slot = invocation.getArgument(0);
			slot.setId(UUID.randomUUID());
			return slot;
		});

		MentorAvailabilitySlotResponse response = mentorProfileService.createAvailabilitySlot(
				mentorId, new CreateMentorAvailabilitySlotRequest(startsAt, null));

		assertThat(response.durationMinutes()).isEqualTo(60);
		assertThat(response.booked()).isFalse();
	}

	@Test
	void deleteAvailabilitySlotRejectsSlotBelongingToAnotherMentor() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		AppUser otherMentor = new AppUser();
		otherMentor.setId(UUID.randomUUID());
		slot.setMentor(otherMentor);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(InvalidRequestException.class);
		verify(mentorAvailabilitySlotRepository, never()).delete(any());
	}

	@Test
	void deleteAvailabilitySlotRejectsAlreadyBookedSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(true);

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(InvalidRequestException.class);
		verify(mentorAvailabilitySlotRepository, never()).delete(any());
	}

	@Test
	void deleteAvailabilitySlotRemovesUnbookedSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(slotId);
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(false);

		mentorProfileService.deleteAvailabilitySlot(mentorId, slotId);

		verify(mentorAvailabilitySlotRepository).delete(slot);
	}

	@Test
	void deleteAvailabilitySlotRejectsUnknownSlot() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		UUID slotId = UUID.randomUUID();
		when(mentorAvailabilitySlotRepository.findById(slotId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mentorProfileService.deleteAvailabilitySlot(mentorId, slotId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void findAvailabilityMarksSlotsWithExistingMeetingsAsBooked() {
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setId(UUID.randomUUID());
		slot.setMentor(mentor());
		slot.setStartsAt(startsAt);
		slot.setDurationMinutes(60);
		when(mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(eq(mentorId), any()))
				.thenReturn(List.of(slot));
		when(meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, startsAt, MeetingStatus.SCHEDULED))
				.thenReturn(true);

		List<MentorAvailabilitySlotResponse> result = mentorProfileService.findAvailability(mentorId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).booked()).isTrue();
	}
}
