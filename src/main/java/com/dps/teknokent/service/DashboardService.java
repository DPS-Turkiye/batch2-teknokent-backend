package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.dto.responses.DashboardSummaryResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardService {

	private static final List<BottleneckStatus> OPEN_BOTTLENECK_STATUSES =
			List.of(BottleneckStatus.OPEN, BottleneckStatus.IN_PROGRESS);
	private static final List<BottleneckSeverity> HIGH_SEVERITIES =
			List.of(BottleneckSeverity.HIGH, BottleneckSeverity.CRITICAL);
	private static final List<ActionItemStatus> CLOSED_ACTION_STATUSES =
			List.of(ActionItemStatus.COMPLETED, ActionItemStatus.CANCELLED);

	private final MentorshipRepository mentorshipRepository;
	private final BottleneckRepository bottleneckRepository;
	private final ActionItemRepository actionItemRepository;
	private final MeetingRepository meetingRepository;

	@Transactional(readOnly = true)
	public DashboardSummaryResponse getSummary(UUID organizationId) {
		// Platform-wide counts are not for a startup's home page; a session without a startup sends a blank id.
		if (organizationId == null) {
			throw new InvalidRequestException("organizationId is required");
		}
		return new DashboardSummaryResponse(
				mentorshipRepository.countByOrganizationIdAndStatus(organizationId, MentorshipStatus.ACTIVE),
				bottleneckRepository.countByOrganizationIdAndStatusIn(organizationId, OPEN_BOTTLENECK_STATUSES),
				bottleneckRepository.countByOrganizationIdAndSeverityInAndStatusIn(
						organizationId, HIGH_SEVERITIES, OPEN_BOTTLENECK_STATUSES),
				actionItemRepository.countByOrganizationIdAndStatusNotInAndDueDateBefore(
						organizationId, CLOSED_ACTION_STATUSES, LocalDate.now()),
				meetingRepository.countByOrganizationIdAndStatusAndScheduledAtAfter(
						organizationId, MeetingStatus.SCHEDULED, Instant.now()));
	}
}
