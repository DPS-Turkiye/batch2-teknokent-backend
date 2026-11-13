package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
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
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.UserRow;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

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
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final MeetingRepository meetingRepository;
	private final BottleneckRepository bottleneckRepository;
	private final LoginProvisioner loginProvisioner;

	@Transactional(readOnly = true)
	public AdminOverviewResponse overview(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!actor.has(UserRole.ADMIN)) {
			throw new InvalidRequestException("Only an admin can view the admin overview.");
		}
		List<AppUser> userEntities = appUserRepository.findByStatusNot(UserAccountStatus.DELETED);
		Map<String, Boolean> passwords = loginProvisioner.passwordsSet(userEntities.stream()
				.filter(AdminOverviewService::mayLackPassword)
				.map(AppUser::getFirebaseUid)
				.toList());
		List<UserRow> users = userEntities.stream()
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase()))
				.map(user -> toUser(user, mayLackPassword(user) ? passwords.get(user.getFirebaseUid()) : null))
				.toList();
		List<RequestRow> requests = mentorshipRequestRepository.findByStatusInOrderByCreatedAtDesc(OPEN_REQUESTS).stream()
				.map(AdminOverviewService::toRequest)
				.toList();
		return build(programRepository.findAllByOrderByCreatedAtDesc(), null, null, userEntities, users, requests);
	}

	/**
	 * The Raporlama numbers for one program, for its coordinators' Program raporu; the caller checks access.
	 * Only that program's teams count, and of their mentorships only the ones made in this program (plus older ones
	 * made before V82, which have none). The user list and open requests are left out.
	 */
	@Transactional(readOnly = true)
	public AdminOverviewResponse programReport(Program program) {
		Set<UUID> teams = programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.map(ProgramStartup::getOrganizationId)
				.collect(Collectors.toSet());
		return build(List.of(program), program.getId(), teams, appUserRepository.findByStatusNot(UserAccountStatus.DELETED), List.of(), List.of());
	}

	/**
	 * {@code teams} limits mentorships, bottlenecks and meetings to those organizations; null keeps them all.
	 * {@code programId} further drops mentorships made in another program; null keeps them all.
	 */
	private AdminOverviewResponse build(
			List<Program> programEntities,
			UUID programId,
			Set<UUID> teams,
			List<AppUser> userEntities,
			List<UserRow> users,
			List<RequestRow> requests) {
		Instant now = Instant.now();
		Predicate<UUID> inScope = organizationId -> teams == null || teams.contains(organizationId);
		Map<UUID, AppUser> usersById = userEntities.stream()
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		Map<UUID, Organization> organizations = new HashMap<>();
		for (Organization organization : organizationRepository.findAll()) {
			organizations.put(organization.getId(), organization);
		}
		List<Meeting> allMeetings = meetingRepository.findAllByOrderByScheduledAtDesc();
		Map<String, Instant> lastMeeting = lastPastMeetingByPair(allMeetings, now);
		List<ProgramRow> programs = programEntities.stream()
				.map(program -> toProgram(program, usersById, organizations))
				.toList();
		List<MentorshipRow> mentorships = mentorshipService.findAll().stream()
				.filter(mentorship -> inScope.test(mentorship.organizationId()))
				.filter(mentorship -> programId == null || mentorship.programId() == null
						|| programId.equals(mentorship.programId()))
				.map(mentorship -> toMentorship(mentorship, lastMeeting, now))
				.toList();
		List<BottleneckRow> bottlenecks = bottleneckRepository.findAll().stream()
				.filter(item -> item.getStatus() == BottleneckStatus.OPEN
						|| item.getStatus() == BottleneckStatus.IN_PROGRESS)
				.filter(item -> inScope.test(item.getOrganization().getId()))
				.map(AdminOverviewService::toBottleneck)
				.toList();
		List<Meeting> meetingEntities = allMeetings.stream()
				.filter(meeting -> meeting.getOrganization() != null && inScope.test(meeting.getOrganization().getId()))
				.toList();
		long meetingsLast8Weeks = meetingEntities.stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
				.filter(meeting -> meeting.getScheduledAt() != null
						&& !meeting.getScheduledAt().isBefore(now.minus(56, ChronoUnit.DAYS))
						&& !meeting.getScheduledAt().isAfter(now))
				.count();
		List<MeetingMark> meetings = meetingEntities.stream()
				.filter(meeting -> meeting.getScheduledAt() != null && meeting.getMentor() != null)
				.map(meeting -> new MeetingMark(
						meeting.getMentor().getId(),
						meeting.getOrganization().getId(),
						meeting.getScheduledAt(),
						meeting.getStatus().name()))
				.toList();
		return new AdminOverviewResponse(
				programs, users, mentorships, requests, bottlenecks, meetingsLast8Weeks, meetings);
	}

	// An added coordinator is APPROVED at once, but cannot sign in before setting a password.
	private static boolean mayLackPassword(AppUser user) {
		return user.has(UserRole.COORDINATOR) && user.getStatus() == UserAccountStatus.APPROVED
				&& user.getFirebaseUid() != null;
	}

	private static UserRow toUser(AppUser user, Boolean passwordSet) {
		return new UserRow(
				user.getId(),
				user.getEmail(),
				user.getFullName(),
				user.getRole(),
				List.copyOf(user.activePermissions()),
				user.getStatus() == null ? UserAccountStatus.APPROVED : user.getStatus(),
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				Organization.visibleName(user.getOrganization()),
				user.getJobTitle(),
				user.getCreatedAt(),
				user.getExpertise() == null ? List.of() : List.copyOf(user.getExpertise()),
				passwordSet);
	}

	private ProgramRow toProgram(
			Program program,
			Map<UUID, AppUser> usersById,
			Map<UUID, Organization> organizations) {
		// The program's coordinators: its creator when that is a coordinator, then the ones added in its settings.
		// An admin who created the program is not its coordinator.
		List<AppUser> coordinators = ProgramCoordination.coordinatorIds(program, programCoordinatorRepository).stream()
				.map(usersById::get)
				.filter(user -> user != null && user.has(UserRole.COORDINATOR))
				.toList();
		List<StartupRow> startups = programStartupRepository
				.findByProgramIdOrderByCreatedAtAsc(program.getId())
				.stream()
				.map(row -> {
					Organization organization = organizations.get(row.getOrganizationId());
					AppUser founder = organization == null ? null : organization.getFounder();
					return new StartupRow(
							row.getOrganizationId(),
							organization == null ? Texts.forRequest("label.team") : organization.getName(),
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
				program.getProgramType().name(),
				program.getCreatedAt(),
				coordinators.isEmpty() ? null : coordinators.get(0).getId(),
				coordinators.isEmpty()
						? null
						: coordinators.stream().map(AppUser::getFullName).collect(Collectors.joining(", ")),
				coordinators.stream().map(AppUser::getId).toList(),
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
				mentorship.goalItems().size(),
				mentorship.programId());
	}

	private static RequestRow toRequest(MentorshipRequest request) {
		return new RequestRow(
				request.getId(),
				request.getOrganization().getId(),
				Organization.visibleName(request.getOrganization()),
				request.getMentor().getId(),
				request.getMentor().getFullName(),
				request.getSupportArea(),
				request.getStatus().name(),
				request.getCreatedAt());
	}

	private static BottleneckRow toBottleneck(Bottleneck bottleneck) {
		return new BottleneckRow(
				bottleneck.getId(),
				bottleneck.getTitle(),
				bottleneck.getSeverity().name(),
				bottleneck.getStatus().name(),
				bottleneck.getOrganization().getId(),
				Organization.visibleName(bottleneck.getOrganization()),
				bottleneck.getCreatedAt());
	}

	private static Map<String, Instant> lastPastMeetingByPair(List<Meeting> meetings, Instant now) {
		Map<String, Instant> last = new HashMap<>();
		for (Meeting meeting : meetings) {
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
