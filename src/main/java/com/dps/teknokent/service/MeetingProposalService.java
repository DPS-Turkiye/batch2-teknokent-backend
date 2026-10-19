package com.dps.teknokent.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
import com.dps.teknokent.dto.requests.RescheduleMeetingRequest;
import com.dps.teknokent.dto.responses.MeetingProposalResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingProposal;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingProposalRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.service.CoordinatorMentorshipService.Scoped;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * Item 9: a coordinator proposes a meeting time for a mentorship. The mentor and the team lead each accept it;
 * once both have, it becomes a normal meeting (or moves the meeting it replaces). One decline closes it.
 * A mentor or a team member can also propose a new time for one of their meetings: their side counts as
 * accepted, and the other side confirms.
 */
@Service
@RequiredArgsConstructor
public class MeetingProposalService {

	private static final Set<MentorshipStatus> OPEN = EnumSet.of(MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE);
	private static final String MENTOR_HOME = "/mentor-dashboard";
	private static final String TEAM_HOME = "/dashboard";

	private final CoordinatorMentorshipService coordinatorMentorshipService;
	private final CoordinatorProgramService programService;
	private final MeetingProposalRepository proposalRepository;
	private final MeetingRepository meetingRepository;
	private final MeetingService meetingService;
	private final AppUserRepository appUserRepository;
	private final ProgramRepository programRepository;
	private final NotificationService notificationService;

	private enum Side {
		MENTOR,
		TEAM
	}

	@Transactional
	public MeetingProposalResponse propose(UUID actorId, UUID mentorshipId, ProposeMeetingRequest request) {
		Scoped scoped = coordinatorMentorshipService.requireManagedMentorship(actorId, mentorshipId);
		Mentorship mentorship = scoped.mentorship();
		if (!OPEN.contains(mentorship.getStatus())) {
			throw new InvalidRequestException(Texts.forRequest("error.proposalNotAllowed"));
		}
		if (!request.scheduledAt().isAfter(Instant.now())) {
			throw new InvalidRequestException(Texts.forRequest("error.meetingInPast"));
		}
		if (proposalRepository.existsByMentorshipIdAndStatus(mentorship.getId(), MeetingProposalStatus.PENDING)) {
			throw new ConflictException(Texts.forRequest("error.mentorshipHasPendingProposal"));
		}
		if (request.replacesMeetingId() != null) {
			Meeting replaced = meetingRepository.findById(request.replacesMeetingId())
					.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + request.replacesMeetingId()));
			if (!samePair(replaced, mentorship) || replaced.getStatus() != MeetingStatus.SCHEDULED) {
				throw new InvalidRequestException(Texts.forRequest("error.rescheduleOnlyPlanned"));
			}
		}

		MeetingProposal proposal = new MeetingProposal();
		proposal.setId(UUID.randomUUID());
		proposal.setMentorship(mentorship);
		proposal.setProgramId(scoped.program().getId());
		proposal.setOrganization(mentorship.getOrganization());
		proposal.setMentor(mentorship.getMentor());
		proposal.setProposedBy(actorId);
		proposal.setScheduledAt(request.scheduledAt());
		proposal.setDurationMinutes(request.durationMinutes());
		proposal.setAgenda(TextUtils.normalizeOptional(request.agenda()));
		proposal.setReplacesMeetingId(request.replacesMeetingId());
		proposal.setStatus(MeetingProposalStatus.PENDING);
		MeetingProposal saved = proposalRepository.save(proposal);

		String titleKey = request.replacesMeetingId() == null
				? "notification.proposalFromCoordinator.title"
				: "notification.newTimeFromCoordinator.title";
		notifyUnanswered(saved, titleKey, "notification.proposalPleaseConfirm.body", pairLine(saved), saved.getScheduledAt());
		return response(saved, scoped.program(), null);
	}

	/** "Yeni saat öner" from the mentor or a team member: the proposer's side has already said yes. */
	@Transactional
	public MeetingProposalResponse proposeReschedule(UUID actorId, UUID meetingId, RescheduleMeetingRequest request) {
		AppUser actor = requireUser(actorId);
		Meeting meeting = meetingRepository.findById(meetingId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + meetingId));
		if (meeting.getStatus() != MeetingStatus.SCHEDULED || meeting.getOrganization() == null
				|| meeting.getMentor() == null) {
			throw new InvalidRequestException(Texts.forRequest("error.newTimeOnlyPlanned"));
		}
		boolean mentorSide = meeting.getMentor().getId().equals(actorId);
		boolean teamSide = actor.getRole() == UserRole.MENTEE && actor.getOrganization() != null
				&& actor.getOrganization().getId().equals(meeting.getOrganization().getId());
		if (!mentorSide && !teamSide) {
			throw new InvalidRequestException(Texts.forRequest("error.newTimeOnlyParticipants"));
		}
		if (!request.scheduledAt().isAfter(Instant.now())) {
			throw new InvalidRequestException(Texts.forRequest("error.meetingInPast"));
		}
		if (proposalRepository.existsByReplacesMeetingIdAndStatus(meetingId, MeetingProposalStatus.PENDING)) {
			throw new ConflictException(Texts.forRequest("error.meetingHasPendingProposal"));
		}

		Instant now = Instant.now();
		MeetingProposal proposal = new MeetingProposal();
		proposal.setId(UUID.randomUUID());
		proposal.setOrganization(meeting.getOrganization());
		proposal.setMentor(meeting.getMentor());
		proposal.setProposedBy(actorId);
		proposal.setScheduledAt(request.scheduledAt());
		proposal.setDurationMinutes(request.durationMinutes() == null
				? meeting.getDurationMinutes()
				: request.durationMinutes());
		proposal.setAgenda(TextUtils.normalizeOptional(meeting.getAgenda()));
		proposal.setReplacesMeetingId(meetingId);
		proposal.setStatus(MeetingProposalStatus.PENDING);
		if (mentorSide) {
			proposal.setMentorAcceptedAt(now);
		} else {
			proposal.setTeamAcceptedAt(now);
			proposal.setTeamResponderId(actorId);
		}
		MeetingProposal saved = proposalRepository.save(proposal);

		notifyUnanswered(saved, "notification.newTimeProposed.title", "notification.proposalPleaseConfirm.body",
				pairLine(saved), saved.getScheduledAt(), actor.getFullName());
		return response(saved, null, actor);
	}

	/** The coordinator's view: one mentorship's proposals, or every proposal in their programs. Newest first. */
	@Transactional(readOnly = true)
	public List<MeetingProposalResponse> coordinatorList(UUID actorId, UUID programId, UUID mentorshipId) {
		if (mentorshipId != null) {
			Scoped scoped = coordinatorMentorshipService.requireManagedMentorship(actorId, mentorshipId);
			return proposalRepository.findByMentorshipIdOrderByCreatedAtDesc(mentorshipId).stream()
					.map(proposal -> response(proposal, scoped.program(), null))
					.toList();
		}
		AppUser actor = programService.requireCoordinatorOrAdmin(actorId);
		Map<UUID, Program> scope = programService.scopeOf(actorId, programService.managedPrograms(actor), programId).stream()
				.collect(Collectors.toMap(Program::getId, Function.identity(), (first, second) -> first));
		if (scope.isEmpty()) {
			return List.of();
		}
		return proposalRepository.findByProgramIdInOrderByCreatedAtDesc(scope.keySet()).stream()
				.map(proposal -> response(proposal, scope.get(proposal.getProgramId()), null))
				.toList();
	}

	/** The mentor's or the team's open proposals, soonest first. */
	@Transactional(readOnly = true)
	public List<MeetingProposalResponse> mine(UUID actorId) {
		AppUser actor = requireUser(actorId);
		List<MeetingProposal> proposals;
		if (actor.getRole() == UserRole.MENTOR) {
			proposals = proposalRepository.findByStatusAndMentorIdOrderByScheduledAtAsc(
					MeetingProposalStatus.PENDING, actorId);
		} else if (actor.getOrganization() != null) {
			proposals = proposalRepository.findByStatusAndOrganizationIdOrderByScheduledAtAsc(
					MeetingProposalStatus.PENDING, actor.getOrganization().getId());
		} else {
			proposals = List.of();
		}
		Map<UUID, Program> programs = programRepository
				.findAllById(proposals.stream().map(MeetingProposal::getProgramId).filter(java.util.Objects::nonNull)
						.distinct().toList()).stream()
				.collect(Collectors.toMap(Program::getId, Function.identity()));
		return proposals.stream()
				.map(proposal -> response(proposal, programs.get(proposal.getProgramId()), actor))
				.toList();
	}

	@Transactional
	public MeetingProposalResponse accept(UUID actorId, UUID proposalId) {
		AppUser actor = requireUser(actorId);
		MeetingProposal proposal = requirePending(proposalId);
		Side side = requireSide(actor, proposal);
		Instant now = Instant.now();
		if (side == Side.MENTOR && proposal.getMentorAcceptedAt() == null) {
			proposal.setMentorAcceptedAt(now);
		}
		if (side == Side.TEAM && proposal.getTeamAcceptedAt() == null) {
			proposal.setTeamAcceptedAt(now);
			proposal.setTeamResponderId(actorId);
		}
		if (proposal.getMentorAcceptedAt() != null && proposal.getTeamAcceptedAt() != null) {
			confirm(proposal);
		}
		MeetingProposal saved = proposalRepository.save(proposal);
		return response(saved, programOf(saved), actor);
	}

	@Transactional
	public MeetingProposalResponse decline(UUID actorId, UUID proposalId, DeclineMeetingProposalRequest request) {
		AppUser actor = requireUser(actorId);
		MeetingProposal proposal = requirePending(proposalId);
		requireSide(actor, proposal);
		proposal.setStatus(MeetingProposalStatus.DECLINED);
		proposal.setDeclinedBy(actorId);
		proposal.setDeclineReason(TextUtils.normalizeOptional(request.reason()));
		MeetingProposal saved = proposalRepository.save(proposal);

		String reason = saved.getDeclineReason() == null ? "" : ": " + saved.getDeclineReason();
		notifyProposer(saved, "notification.proposalDeclined.title", "notification.proposalDeclined.body",
				actor.getFullName(), pairLine(saved), saved.getScheduledAt(), reason);
		return response(saved, programOf(saved), actor);
	}

	@Transactional
	public void withdraw(UUID actorId, UUID proposalId) {
		MeetingProposal proposal = requireManagedPending(actorId, proposalId);
		proposal.setStatus(MeetingProposalStatus.WITHDRAWN);
		proposalRepository.save(proposal);
	}

	/** "Hatırlat": nudges whoever has not answered yet. */
	@Transactional
	public void remind(UUID actorId, UUID proposalId) {
		MeetingProposal proposal = requireManagedPending(actorId, proposalId);
		proposal.setRemindedAt(Instant.now());
		proposalRepository.save(proposal);
		notifyUnanswered(proposal, "notification.proposalWaiting.title", "notification.proposalWaiting.body",
				pairLine(proposal), proposal.getScheduledAt());
	}

	/** Both sides said yes: move the replaced meeting if it is still planned, otherwise book a new one. */
	private void confirm(MeetingProposal proposal) {
		Meeting replaced = proposal.getReplacesMeetingId() == null
				? null
				: meetingRepository.findById(proposal.getReplacesMeetingId())
						.filter(meeting -> meeting.getStatus() == MeetingStatus.SCHEDULED)
						.orElse(null);
		boolean botReleased = false;
		if (replaced != null) {
			// ponytail: moves only this meeting, not guest copies in a shared room; add roommates if group reschedules matter.
			replaced.setScheduledAt(proposal.getScheduledAt());
			replaced.setDurationMinutes(proposal.getDurationMinutes());
			replaced.setReminderSentAt(null);
			botReleased = meetingService.releaseTranscriptBotIfFull(replaced);
			meetingRepository.save(replaced);
			proposal.setMeetingId(replaced.getId());
		} else {
			String agenda = proposal.getAgenda() == null ? "Mentorluk görüşmesi" : proposal.getAgenda();
			proposal.setMeetingId(meetingService.create(new CreateMeetingRequest(
					proposal.getOrganization().getId(),
					proposal.getMentor().getId(),
					proposal.getScheduledAt(),
					proposal.getDurationMinutes(),
					agenda,
					null,
					null,
					null,
					MeetingStatus.SCHEDULED,
					false,
					List.of())).id());
		}
		proposal.setStatus(MeetingProposalStatus.CONFIRMED);

		String bodyKey = botReleased ? "notification.proposalConfirmedNoBot.body" : "notification.proposalConfirmed.body";
		Object[] args = {pairLine(proposal), proposal.getScheduledAt()};
		notifyProposer(proposal, "notification.proposalConfirmed.title", bodyKey, args);
		if (!proposal.getMentor().getId().equals(proposal.getProposedBy())) {
			notify(proposal.getMentor(), "notification.proposalConfirmed.title", bodyKey, args, "/mentor-dashboard/calendar");
		}
		if (replaced != null) {
			for (AppUser member : members(proposal.getOrganization())) {
				notify(member, "notification.meetingMoved.title", bodyKey, args, "/mentorships?tab=active");
			}
		}
	}

	private void notifyUnanswered(MeetingProposal proposal, String titleKey, String bodyKey, Object... args) {
		if (proposal.getMentorAcceptedAt() == null) {
			notify(proposal.getMentor(), titleKey, bodyKey, args, MENTOR_HOME);
		}
		if (proposal.getTeamAcceptedAt() == null) {
			for (AppUser responder : teamResponders(proposal.getOrganization())) {
				notify(responder, titleKey, bodyKey, args, TEAM_HOME);
			}
		}
	}

	/** The coordinator lands on the mentorship's drawer; a mentor or a team member on their own page. */
	private void notifyProposer(MeetingProposal proposal, String titleKey, String bodyKey, Object... args) {
		appUserRepository.findById(proposal.getProposedBy())
				.ifPresent(proposer -> notify(proposer, titleKey, bodyKey, args, proposerLink(proposal, proposer)));
	}

	/** Title and body are written in the reader's language, from the same arguments. */
	private void notify(AppUser reader, String titleKey, String bodyKey, Object[] args, String href) {
		notificationService.create(reader, NotificationCategory.MEETING_CHANGE, Texts.get(reader, titleKey, args),
				Texts.get(reader, bodyKey, args), href);
	}

	private static String proposerLink(MeetingProposal proposal, AppUser proposer) {
		if (proposal.getMentorship() != null
				&& (proposer.getRole() == UserRole.COORDINATOR || proposer.getRole() == UserRole.ADMIN)) {
			return "/coordinator/mentorships?mentorship=" + proposal.getMentorship().getId();
		}
		return proposer.getRole() == UserRole.MENTOR ? "/mentor-dashboard/calendar" : TEAM_HOME;
	}

	private Program programOf(MeetingProposal proposal) {
		return proposal.getProgramId() == null ? null : programRepository.findById(proposal.getProgramId()).orElse(null);
	}

	/** The team lead answers for the team; a team without a lead lets any member answer. */
	private List<AppUser> teamResponders(Organization organization) {
		AppUser founder = organization.getFounder();
		return founder != null ? List.of(founder) : members(organization);
	}

	private List<AppUser> members(Organization organization) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.toList();
	}

	private Side sideOf(AppUser actor, MeetingProposal proposal) {
		if (proposal.getMentor().getId().equals(actor.getId())) {
			return Side.MENTOR;
		}
		boolean responder = teamResponders(proposal.getOrganization()).stream()
				.anyMatch(user -> user.getId().equals(actor.getId()));
		return responder ? Side.TEAM : null;
	}

	private Side requireSide(AppUser actor, MeetingProposal proposal) {
		Side side = sideOf(actor, proposal);
		if (side == null) {
			throw new InvalidRequestException(Texts.forRequest("error.proposalAnswerOnlyParticipants"));
		}
		return side;
	}

	private MeetingProposal requirePending(UUID proposalId) {
		MeetingProposal proposal = proposalRepository.findById(proposalId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting proposal not found: " + proposalId));
		if (!proposal.isPending()) {
			throw new ConflictException(Texts.forRequest("error.proposalNotPending"));
		}
		return proposal;
	}

	private MeetingProposal requireManagedPending(UUID actorId, UUID proposalId) {
		MeetingProposal proposal = requirePending(proposalId);
		if (proposal.getMentorship() == null) {
			throw new InvalidRequestException(Texts.forRequest("error.proposalNotProgram"));
		}
		coordinatorMentorshipService.requireManagedMentorship(actorId, proposal.getMentorship().getId());
		return proposal;
	}

	private AppUser requireUser(UUID actorId) {
		return appUserRepository.findById(actorId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + actorId));
	}

	private static boolean samePair(Meeting meeting, Mentorship mentorship) {
		return meeting.getOrganization() != null
				&& meeting.getOrganization().getId().equals(mentorship.getOrganization().getId())
				&& meeting.getMentor() != null
				&& meeting.getMentor().getId().equals(mentorship.getMentor().getId());
	}

	private static String pairLine(MeetingProposal proposal) {
		return proposal.getMentor().getFullName() + " ↔ " + proposal.getOrganization().getName();
	}

	private MeetingProposalResponse response(MeetingProposal proposal, Program program, AppUser viewer) {
		Side side = viewer == null ? null : sideOf(viewer, proposal);
		boolean canRespond = proposal.isPending()
				&& ((side == Side.MENTOR && proposal.getMentorAcceptedAt() == null)
						|| (side == Side.TEAM && proposal.getTeamAcceptedAt() == null));
		return new MeetingProposalResponse(
				proposal.getId(),
				proposal.getMentorship() == null ? null : proposal.getMentorship().getId(),
				proposal.getProgramId(),
				program == null ? null : program.getName(),
				proposal.getMentor().getId(),
				proposal.getMentor().getFullName(),
				proposal.getOrganization().getId(),
				proposal.getOrganization().getName(),
				nameOf(proposal.getProposedBy()),
				proposal.getScheduledAt(),
				proposal.getDurationMinutes(),
				proposal.getAgenda(),
				proposal.getReplacesMeetingId(),
				proposal.getStatus(),
				proposal.getMentorAcceptedAt(),
				proposal.getTeamAcceptedAt(),
				nameOf(proposal.getTeamResponderId()),
				nameOf(proposal.getDeclinedBy()),
				proposal.getDeclineReason(),
				proposal.getMeetingId(),
				proposal.getRemindedAt(),
				proposal.getCreatedAt(),
				canRespond);
	}

	private String nameOf(UUID userId) {
		return userId == null ? null : appUserRepository.findById(userId).map(AppUser::getFullName).orElse(null);
	}
}
