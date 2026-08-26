package com.dps.teknokent.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.repository.MeetingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingRoomAccessServiceTest {

	@Mock
	private MeetingRepository meetingRepository;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

	@Test
	void allowsSubscriptionsOnlyForMeetingsOfTheCallerOrganization() {
		when(meetingRepository.existsByIdAndOrganizationId(meetingId, organizationId)).thenReturn(true);
		MeetingRoomAccessService service = new MeetingRoomAccessService(meetingRepository);

		assertThat(service.isMeetingVisibleTo(meetingId, organizationId)).isTrue();
	}

	@Test
	void refusesSubscriptionsForUnknownOrForeignMeetings() {
		UUID otherOrganizationId = UUID.randomUUID();
		when(meetingRepository.existsByIdAndOrganizationId(meetingId, otherOrganizationId)).thenReturn(false);
		MeetingRoomAccessService service = new MeetingRoomAccessService(meetingRepository);

		assertThat(service.isMeetingVisibleTo(meetingId, otherOrganizationId)).isFalse();
	}
}
