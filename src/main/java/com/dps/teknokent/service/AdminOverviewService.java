package com.dps.teknokent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
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
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MeetingMark;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MentorMembershipRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.MentorshipRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.ProgramRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.RequestRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.StartupRow;
import com.dps.teknokent.dto.responses.AdminOverviewResponse.UserRow;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.AdminAccess;
import com.dps.teknokent.security.LoginProvisioner;
import com.dps.teknokent.util.ProgramActivity;
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
	private static final List<BottleneckStatus> OPEN_BOTTLENECKS = List.of(
			BottleneckStatus.OPEN, BottleneckStatus.IN_PROGRESS);

	private final DomainLookupService domainLookupService;
	private final AppUserRepository appUserRepository;
	private final MentorshipService mentorshipService;
	private final MentorshipRepository mentorshipRepository;
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
		AdminAccess.requireAdmin(domainLookupService.requireUser(actorId));
		List<AppUser> userEntities = appUserRepository.findByStatusNot(UserAccountStatus.DELETED);
		Map<String, Boolean> passwords = loginProvisioner.passwordsSet(userEntities.stream()
				.filter(AdminOverviewService::mayLackPassword)
				.map(AppUser::getFirebaseUid)
				.toList());
		List<UserRow> users = userEntities.stream()
				.sorted(Comparator.comparing(user -> user.getFullName().toLowerCase(Locale.ROOT)))
				.map(user -> toUser(user, mayLackPassword(user) ? passwords.get(user.getFirebaseUid()) : null))
				.toList();
		List<RequestRow> requests = mentorshipRequestRepository.findByStatusInOrderByCreatedAtDesc(OPEN_REQUESTS)
				.stream()
				.map(AdminOverviewService::toRequest)
				.toList();
		Sources sources = new Sources(
				userEntities.stream().collect(Collectors.toMap(AppUser::getId, Function.identity())),
				organizationRepository.findAll(),
				meetingRepository.findAllByOrderByScheduledAtDesc(),
				mentorshipService.findAll(),
				bottleneckRepository.findAll());
		return build(programRepository.findAllByOrderByCreatedAtDesc(), null, null, Set.of(), sources, users,
				requests);
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
		// A team that made a mentorship here and has since left (an admin moved it to another program) still
		// counts here with what it did here.
		Set<UUID> formerTeams = mentorshipRepository.findTeamsWithMentorshipIn(List.of(program.getId())).stream()
				.map(MentorshipRepository.ProgramTeam::getOrganizationId)
				.filter(organizationId -> !teams.contains(organizationId))
				.collect(Collectors.toSet());
		// Only the rows this program can show are loaded, not the whole platform's.
		Set<UUID> reached = new HashSet<>(teams);
		reached.addAll(formerTeams);
		Sources sources = new Sources(
				peopleOf(program),
				teams.isEmpty() ? List.of() : organizationRepository.findAllById(teams),
				reached.isEmpty() ? List.of() : meetingRepository.findByOrganizationIdInOrderByScheduledAtDesc(reached),
				reached.isEmpty() ? List.of() : mentorshipService.findByOrganizations(reached),
				teams.isEmpty() ? List.of()
						: bottleneckRepository.findByOrganizationIdInAndStatusIn(teams, OPEN_BOTTLENECKS));
		return build(List.of(program), program.getId(), teams, formerTeams, sources, List.of(), List.of());
	}

	/** The rows a report is built from: every one for the overview, only a program's own for its report. */
	private record Sources(
			Map<UUID, AppUser> usersById,
			List<Organization> organizations,
			List<Meeting> meetings,
			List<MentorshipResponse> mentorships,
			List<Bottleneck> bottlenecks) {
	}

	/** The accounts a program row names: its coordinators and its pool's mentors. A deleted account names nobody. */
	private Map<UUID, AppUser> peopleOf(Program program) {
		Set<UUID> ids = new HashSet<>(ProgramCoordination.coordinatorIds(program, programCoordinatorRepository));
		programMentorRepository.findByProgramIdOrderByCreatedAtAsc(program.getId()).stream()
				.map(ProgramMentor::getMentorId)
				.forEach(ids::add);
		return appUserRepository.findAllById(ids).stream()
				.filter(user -> user.getStatus() != UserAccountStatus.DELETED)
				.collect(Collectors.toMap(AppUser::getId, Function.identity()));
	}

	/**
	 * {@code teams} limits mentorships, bottlenecks and meetings to those organizations; null keeps them all.
	 * {@code programId} further drops mentorships and meetings of another program; null keeps them all.
	 * {@code formerTeams} are counted only with the mentorships and meetings they had in that program.
	 */
	private AdminOverviewResponse build(
			List<Program> programEntities,
			@Nullable UUID programId,
			@Nullable Set<UUID> teams,
			Set<UUID> formerTeams,
			Sources sources,
			List<UserRow> users,
			List<RequestRow> requests) {
		Instant now = Instant.now();
		Predicate<UUID> inScope = organizationId -> teams == null || teams.contains(organizationId);
		Map<UUID, AppUser> usersById = sources.usersById();
		Map<UUID, Organization> organizations = new HashMap<>();
		for (Organization organization : sources.organizations()) {
			organizations.put(organization.getId(), organization);
		}
		List<Meeting> allMeetings = sources.meetings();
		Map<String, Instant> lastMeeting = lastPastMeetingByPair(allMeetings, now);
		List<ProgramRow> programs = programEntities.stream()
				.map(program -> toProgram(program, usersById, organizations))
				.toList();
		List<MentorshipRow> mentorships = sources.mentorships().stream()
				.filter(mentorship -> inScope.test(mentorship.organizationId())
						|| formerTeams.contains(mentorship.organizationId()))
				// One made before V82 has no program and counts while its team is still here.
				.filter(mentorship -> programId == null || (mentorship.programId() == null
						? inScope.test(mentorship.organizationId())
						: programId.equals(mentorship.programId())))
				.map(mentorship -> toMentorship(mentorship, lastMeeting, now))
				.toList();
		List<BottleneckRow> bottlenecks = sources.bottlenecks().stream()
				.filter(item -> OPEN_BOTTLENECKS.contains(item.getStatus()))
				.filter(item -> inScope.test(item.getOrganization().getId()))
				.map(AdminOverviewService::toBottleneck)
				.toList();
		List<Meeting> ofTheseTeams = allMeetings.stream()
				.filter(meeting -> meeting.getOrganization() != null
						&& (inScope.test(meeting.getOrganization().getId())
								|| formerTeams.contains(meeting.getOrganization().getId())))
				.toList();
		// A program report always comes with its teams; the platform-wide overview has neither.
		List<Meeting> meetingEntities = programId == null || teams == null
				? ofTheseTeams
				: ProgramActivity.heldIn(programId, ofTheseTeams, teams, mentorshipsByTeam(teams, formerTeams));
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
						meeting.getStatus()))
				.toList();
		return new AdminOverviewResponse(
				programs, users, mentorships, requests, bottlenecks, meetingsLast8Weeks, meetings);
	}

	private Map<UUID, List<Mentorship>> mentorshipsByTeam(Set<UUID> teams, Set<UUID> formerTeams) {
		Set<UUID> all = new HashSet<>(teams);
		all.addAll(formerTeams);
		return all.isEmpty()
				? Map.of()
				: mentorshipRepository.findByOrganizationIdIn(all).stream()
						.collect(Collectors.groupingBy(mentorship -> mentorship.getOrganization().getId()));
	}

	// An added coordinator is APPROVED at once, but cannot sign in before setting a password.
	private static boolean mayLackPassword(AppUser user) {
		return user.has(UserRole.COORDINATOR) && user.getStatus() == UserAccountStatus.APPROVED
				&& user.getFirebaseUid() != null;
	}

	private static UserRow toUser(AppUser user, @Nullable Boolean passwordSet) {
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
							membership.getStatus());
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
				program.getStatus(),
				program.getProgramType(),
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
				mentorship.status(),
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
				request.getStatus(),
				request.getCreatedAt());
	}

	private static BottleneckRow toBottleneck(Bottleneck bottleneck) {
		return new BottleneckRow(
				bottleneck.getId(),
				bottleneck.getTitle(),
				bottleneck.getSeverity(),
				bottleneck.getStatus(),
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
