package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminOverviewResponse;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.BottleneckRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MentorMembershipRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MeetingMark;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MentorshipRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.ProgramRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.RequestRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.StartupRow;
import com.dps.teknokent.dto.responses.MentorshipRequestResponse;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.UserRow;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.ProgramCoordination;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminOverviewService {

	private static final List<MentorshipRequestStatus> OPEN_REQUESTS = List.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED);

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final MentorshipService mentorshipService;
	private final MentorshipRequestService mentorshipRequestService;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final MeetingRepository meetingRepository;
	private final BottleneckRepository bottleneckRepository;

	@Transactional(readOnly = true)
	public AdminOverviewResponse overview(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can view the admin overview.");
		}
		Instant now = Instant.now();
		List<AppUser> userEntities = appUserRepository.findAll();
		Map<UUID, AppUser> usersById = userEntities.stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		List<UserRow> users = userEntities.stream()
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase()))
				.map(AdminOverviewService::toUser)
				.toList();
		Map<UUID, Organization> organizations = new HashMap<>();
		for (Organization organization : organizationRepository.findAll()) {
			organizations.put(organization.getId(), organization);
		}
		Map<String, Instant> lastMeeting = lastPastMeetingByPair(now);
		List<ProgramRow> programs = programRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(program -> toProgram(program, usersById, organizations))
				.toList();
		List<MentorshipRow> mentorships = mentorshipService.findAll().stream()
				.map(mentorship -> toMentorship(mentorship, lastMeeting, now))
				.toList();
		List<RequestRow> requests = mentorshipRequestService.findAll(null, null, null).stream()
				.filter(request -> OPEN_REQUESTS.contains(request.status()))
				.map(AdminOverviewService::toRequest)
				.toList();
		List<BottleneckRow> bottlenecks = bottleneckRepository.findAll().stream()
				.filter(item -> item.getStatus() == BottleneckStatus.OPEN
						|| item.getStatus() == BottleneckStatus.IN_PROGRESS)
				.map(AdminOverviewService::toBottleneck)
				.toList();
		List<Meeting> meetingEntities = meetingRepository.findAllByOrderByScheduledAtDesc();
		long meetingsLast8Weeks = meetingEntities.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
				.filter(meeting -> meeting.getScheduledAt() != null
						&& !meeting.getScheduledAt().isBefore(now.minus(56, ChronoUnit.DAYS))
						&& !meeting.getScheduledAt().isAfter(now))
				.count();
		List<MeetingMark> meetings = meetingEntities.stream()
				.filter(meeting -> meeting.getScheduledAt() != null
						&& meeting.getMentor() != null
						&& meeting.getOrganization() != null)
				.map(meeting -> new MeetingMark(
						meeting.getMentor().getId(),
						meeting.getOrganization().getId(),
						meeting.getScheduledAt(),
						meeting.getStatus().name()))
				.toList();
		return new AdminOverviewResponse(
				programs, users, mentorships, requests, bottlenecks, meetingsLast8Weeks, meetings);
	}

	private static UserRow toUser(AppUser user) {
		return new UserRow(
				user.getId(),
				user.getEmail(),
				user.getFullName(),
				user.getRole(),
				user.getStatus() == null ? UserAccountStatus.APPROVED : user.getStatus(),
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getOrganization() == null ? null : user.getOrganization().getName(),
				user.getJobTitle(),
				user.getCreatedAt(),
				user.getExpertise() == null ? List.of() : List.copyOf(user.getExpertise()));
	}

	private ProgramRow toProgram(
			Program program,
			Map<UUID, AppUser> usersById,
			Map<UUID, Organization> organizations) {
		// The program's coordinators: its creator when that is a coordinator, then the ones added in its settings.
		// An admin who created the program is not its coordinator.
		List<AppUser> coordinators = ProgramCoordination.coordinatorIds(program, programCoordinatorRepository).stream()
				.map(usersById::get)
				.filter(user -> user != null && user.getRole() == UserRole.COORDINATOR)
				.toList();
		List<StartupRow> startups = programStartupRepository
				.findByProgramIdOrderByCreatedAtAsc(program.getId())
				.stream()
				.map(row -> {
					Organization organization = organizations.get(row.getOrganizationId());
					AppUser founder = organization == null ? null : organization.getFounder();
					return new StartupRow(
							row.getOrganizationId(),
							organization == null ? "Takım" : organization.getName(),
							organization == null || organization.getSector() == null
									? null
									: organization.getSector().value(),
							program.getId(),
							program.getName(),
							founder == null ? null : founder.getFullName(),
							founder == null ? null : founder.getJobTitle());
				})
				.toList();
		List<ProgramMentor> memberships = programMentorRepository
				.findByProgramIdOrderByCreatedAtAsc(program.getId());
		List<MentorMembershipRow> mentors = memberships.stream()
				.map(membership -> {
					AppUser mentor = usersById.get(membership.getMentorId());
					return new MentorMembershipRow(
							membership.getMentorId(),
							mentor == null ? "Mentor" : mentor.getFullName(),
							mentor == null ? "" : mentor.getEmail(),
							membership.getStatus().name());
				})
				.toList();
		int pending = (int) memberships.stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.PENDING)
				.count();
		int accepted = (int) memberships.stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.count();
		return new ProgramRow(
				program.getId(),
				program.getName(),
				program.getStatus().name(),
				program.getCreatedAt(),
				coordinators.isEmpty() ? null : coordinators.get(0).getId(),
				coordinators.isEmpty()
						? null
						: coordinators.stream().map(AppUser::getFullName).collect(Collectors.joining(", ")),
				startups.size(),
				accepted,
				pending,
				startups,
				mentors);
	}

	private static MentorshipRow toMentorship(
			MentorshipResponse mentorship,
			Map<String, Instant> lastMeeting,
			Instant now) {
		Instant last = lastMeeting.get(pairKey(mentorship.mentorId(), mentorship.organizationId()));
		Long days = last == null ? null : ChronoUnit.DAYS.between(last, now);
		return new MentorshipRow(
				mentorship.id(),
				mentorship.mentorId(),
				mentorship.mentorName(),
				mentorship.organizationId(),
				mentorship.organizationName(),
				mentorship.status().name(),
				mentorship.startDate(),
				mentorship.endDate(),
				mentorship.progressPercent(),
				days,
				(int) mentorship.goalItems().stream().filter(goal -> goal.done()).count(),
				mentorship.goalItems().size());
	}

	private static RequestRow toRequest(MentorshipRequestResponse request) {
		return new RequestRow(
				request.id(),
				request.organizationId(),
				request.organizationName(),
				request.mentorId(),
				request.mentorName(),
				request.supportArea(),
				request.status().name(),
				request.createdAt());
	}

	private static BottleneckRow toBottleneck(Bottleneck bottleneck) {
		return new BottleneckRow(
				bottleneck.getId(),
				bottleneck.getTitle(),
				bottleneck.getSeverity().name(),
				bottleneck.getStatus().name(),
				bottleneck.getOrganization().getId(),
				bottleneck.getOrganization().getName(),
				bottleneck.getCreatedAt());
	}

	private Map<String, Instant> lastPastMeetingByPair(Instant now) {
		Map<String, Instant> last = new HashMap<>();
		for (Meeting meeting : meetingRepository.findAllByOrderByScheduledAtDesc()) {
			if (meeting.getStatus() == MeetingStatus.CANCELLED || meeting.getScheduledAt() == null) {
				continue;
			}
			if (meeting.getScheduledAt().isAfter(now) || meeting.getMentor() == null
					|| meeting.getOrganization() == null) {
				continue;
			}
			last.putIfAbsent(
					pairKey(meeting.getMentor().getId(), meeting.getOrganization().getId()),
					meeting.getScheduledAt());
		}
		return last;
	}

	private static String pairKey(UUID mentorId, UUID organizationId) {
		return mentorId + ":" + organizationId;
	}
}
