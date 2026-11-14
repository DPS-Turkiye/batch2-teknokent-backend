package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

	@Mock
	private MentorshipRepository mentorshipRepository;

	@Mock
	private BottleneckRepository bottleneckRepository;

	@Mock
	private ActionItemRepository actionItemRepository;

	@Mock
	private MeetingRepository meetingRepository;

	@Mock
	private StartupAccessService startupAccessService;

	@InjectMocks
	private DashboardService dashboardService;

	@Test
	void getSummary_forOrganization_returnsCountsForThatOrganization() {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

		when(mentorshipRepository.countByOrganizationIdAndStatus(organizationId, MentorshipStatus.ACTIVE))
				.thenReturn(3L);
		when(bottleneckRepository.countByOrganizationIdAndStatusIn(eq(organizationId), any())).thenReturn(2L);
		when(bottleneckRepository.countByOrganizationIdAndSeverityInAndStatusIn(
				eq(organizationId), any(), any())).thenReturn(1L);
		when(actionItemRepository.countByOrganizationIdAndStatusNotInAndDueDateBefore(
				eq(organizationId), any(), any(LocalDate.class))).thenReturn(4L);
		when(meetingRepository.countByOrganizationIdAndStatusAndScheduledAtAfter(
				eq(organizationId), eq(MeetingStatus.SCHEDULED), any(Instant.class))).thenReturn(5L);

		var result = dashboardService.getSummary(organizationId, null);

		assertThat(result.activeMentorships()).isEqualTo(3L);
		assertThat(result.openBottlenecks()).isEqualTo(2L);
		assertThat(result.highSeverityBottlenecks()).isEqualTo(1L);
		assertThat(result.overdueActions()).isEqualTo(4L);
		assertThat(result.upcomingMeetings()).isEqualTo(5L);
	}

	@Test
	void getSummary_withoutAnOrganization_refusesInsteadOfCountingThePlatform() {
		assertThatThrownBy(() -> dashboardService.getSummary(null, null))
				.isInstanceOf(InvalidRequestException.class);

		verifyNoInteractions(mentorshipRepository, bottleneckRepository, actionItemRepository, meetingRepository);
	}

	@Test
	void getSummary_refusesANamedCallerFromOutsideTheStartup() {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID outsider = UUID.fromString("00000000-0000-0000-0000-000000000201");
		when(startupAccessService.requireTeamOrStaff(outsider, organizationId))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> dashboardService.getSummary(organizationId, outsider))
				.isInstanceOf(ResponseStatusException.class);

		verifyNoInteractions(mentorshipRepository, bottleneckRepository, actionItemRepository, meetingRepository);
	}

	@Test
	void getSummary_countsForANamedMemberOfTheStartup() {
		UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID member = UUID.fromString("00000000-0000-0000-0000-000000000202");
		when(mentorshipRepository.countByOrganizationIdAndStatus(organizationId, MentorshipStatus.ACTIVE))
				.thenReturn(3L);

		assertThat(dashboardService.getSummary(organizationId, member).activeMentorships()).isEqualTo(3L);
		verify(startupAccessService).requireTeamOrStaff(member, organizationId);
	}
}
