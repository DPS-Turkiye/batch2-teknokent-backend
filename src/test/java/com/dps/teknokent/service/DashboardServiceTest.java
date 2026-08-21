package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
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

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void getSummary_forOrganization_returnsCountsForThatOrganization() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        when(mentorshipRepository.countByOrganizationIdAndStatus(organizationId, MentorshipStatus.ACTIVE)).thenReturn(3L);
        when(bottleneckRepository.countByOrganizationIdAndStatusIn(eq(organizationId), any())).thenReturn(2L);
        when(bottleneckRepository.countByOrganizationIdAndSeverityInAndStatusIn(
                eq(organizationId), any(), any())).thenReturn(1L);
        when(actionItemRepository.countByOrganizationIdAndStatusNotInAndDueDateBefore(
                eq(organizationId), any(), any(LocalDate.class))).thenReturn(4L);
        when(meetingRepository.countByOrganizationIdAndStatusAndScheduledAtAfter(
                eq(organizationId), eq(MeetingStatus.SCHEDULED), any(Instant.class))).thenReturn(5L);

        var result = dashboardService.getSummary(organizationId);

        assertThat(result.activeMentorships()).isEqualTo(3L);
        assertThat(result.openBottlenecks()).isEqualTo(2L);
        assertThat(result.highSeverityBottlenecks()).isEqualTo(1L);
        assertThat(result.overdueActions()).isEqualTo(4L);
        assertThat(result.upcomingMeetings()).isEqualTo(5L);
    }

    @Test
    void getSummary_forAllOrganizations_returnsGlobalCounts() {
        when(mentorshipRepository.countByStatus(MentorshipStatus.ACTIVE)).thenReturn(9L);
        when(bottleneckRepository.countByStatusIn(any())).thenReturn(7L);
        when(bottleneckRepository.countBySeverityInAndStatusIn(any(), any())).thenReturn(3L);
        when(actionItemRepository.countByStatusNotInAndDueDateBefore(any(), any(LocalDate.class))).thenReturn(11L);
        when(meetingRepository.countByStatusAndScheduledAtAfter(eq(MeetingStatus.SCHEDULED), any(Instant.class))).thenReturn(13L);

        var result = dashboardService.getSummary(null);

        assertThat(result.activeMentorships()).isEqualTo(9L);
        assertThat(result.openBottlenecks()).isEqualTo(7L);
        assertThat(result.highSeverityBottlenecks()).isEqualTo(3L);
        assertThat(result.overdueActions()).isEqualTo(11L);
        assertThat(result.upcomingMeetings()).isEqualTo(13L);
    }
}
