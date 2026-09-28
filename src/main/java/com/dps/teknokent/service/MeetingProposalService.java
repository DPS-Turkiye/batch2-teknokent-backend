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
import com.dps.teknokent.domain.TranscriptBotConstants;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMeetingRequest;
import com.dps.teknokent.dto.requests.DeclineMeetingProposalRequest;
import com.dps.teknokent.dto.requests.ProposeMeetingRequest;
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
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.service.CoordinatorMentorshipService.Scoped;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

/**
 * Item 9: a coordinator proposes a meeting time for a mentorship. The mentor and the team lead each accept it;
 * once both have, it becomes a normal meeting (or moves the meeting it replaces). One decline closes it.
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
	private final MentorshipRepository mentorshipRepository;
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
		// Serialize proposal creation on the parent row. The partial unique index remains the
		// database-level guard for all writers, including callers that do not take this lock.
		Mentorship mentorship = mentorshipRepository.findByIdForUpdate(mentorshipId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + mentorshipId));
		if (!OPEN.contains(mentorship.getStatus())) {
			throw new InvalidRequestException("Bu mentorluk için görüşme önerilemez.");
		}
		if (!request.scheduledAt().isAfter(Instant.now())) {
			throw new InvalidRequestException("Görüşme zamanı gelecekte olmalı.");
		}
		if (proposalRepository.existsByMentorshipIdAndStatus(mentorship.getId(), MeetingProposalStatus.PENDING)) {
			throw new ConflictException("Bu mentorluk için yanıt bekleyen bir görüşme önerisi var.");
		}
		if (request.replacesMeetingId() != null) {
			Meeting replaced = meetingRepository.findById(request.replacesMeetingId())
					.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + request.replacesMeetingId()));
			if (!samePair(replaced, mentorship) || replaced.getStatus() != MeetingStatus.SCHEDULED) {
				throw new InvalidRequestException("Yalnızca bu mentorluğun planlanmış bir görüşmesi yeniden planlanabilir.");
			}
		}

		MeetingProposal proposal = new MeetingProposal();
		proposal.setId(UUID.randomUUID());
		proposal.setMentorship(mentorship);
		proposal.setProgramId(scoped.program().getId());
		proposal.setProposedBy(actorId);
		proposal.setScheduledAt(request.scheduledAt());
		proposal.setDurationMinutes(request.durationMinutes());
		proposal.setAgenda(TextUtils.normalizeOptional(request.agenda()));
		proposal.setReplacesMeetingId(request.replacesMeetingId());
		proposal.setStatus(MeetingProposalStatus.PENDING);
		MeetingProposal saved = proposalRepository.save(proposal);

		String title = request.replacesMeetingId() == null ? "Koordinatörden görüşme önerisi" : "Koordinatörden yeni saat önerisi";
		notifyUnanswered(saved, title, pairLine(saved) + " · " + MeetingSlotTimes.formatForPeople(saved.getScheduledAt())
				+ ". Uygunsan onayla.");
		return response(saved, scoped.program(), null);
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
			proposals = proposalRepository.findByStatusAndMentorshipMentorIdOrderByScheduledAtAsc(
					MeetingProposalStatus.PENDING, actorId);
		} else if (actor.getOrganization() != null) {
			proposals = proposalRepository.findByStatusAndMentorshipOrganizationIdOrderByScheduledAtAsc(
					MeetingProposalStatus.PENDING, actor.getOrganization().getId());
		} else {
			proposals = List.of();
		}
		Map<UUID, Program> programs = programRepository
				.findAllById(proposals.stream().map(MeetingProposal::getProgramId).distinct().toList()).stream()
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
		return response(saved, programRepository.findById(saved.getProgramId()).orElse(null), actor);
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
		notifyCoordinator(saved, "Görüşme önerisi reddedildi", actor.getFullName() + " · " + pairLine(saved) + " · "
				+ MeetingSlotTimes.formatForPeople(saved.getScheduledAt()) + reason);
		return response(saved, programRepository.findById(saved.getProgramId()).orElse(null), actor);
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
		notifyUnanswered(proposal, "Görüşme önerisi yanıt bekliyor", pairLine(proposal) + " · "
				+ MeetingSlotTimes.formatForPeople(proposal.getScheduledAt()) + ". Onaylamayı unutma.");
	}

	/** Both sides said yes: move the replaced meeting if it is still planned, otherwise book a new one. */
	private void confirm(MeetingProposal proposal) {
		Mentorship mentorship = proposal.getMentorship();
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
					mentorship.getOrganization().getId(),
					mentorship.getMentor().getId(),
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

		String body = pairLine(proposal) + " · " + MeetingSlotTimes.formatForPeople(proposal.getScheduledAt());
		if (botReleased) {
			body += " " + TranscriptBotConstants.CAPACITY_FULL_MESSAGE;
		}
		notifyCoordinator(proposal, "Görüşme onaylandı", body);
		notificationService.create(mentorship.getMentor(), NotificationCategory.MEETING_CHANGE, "Görüşme onaylandı",
				body, "/mentor-dashboard/calendar");
		if (replaced != null) {
			for (AppUser member : members(mentorship.getOrganization())) {
				notificationService.create(member, NotificationCategory.MEETING_CHANGE, "Görüşme yeni saate taşındı",
						body, "/mentorships?tab=active");
			}
		}
	}

	private void notifyUnanswered(MeetingProposal proposal, String title, String body) {
		Mentorship mentorship = proposal.getMentorship();
		if (proposal.getMentorAcceptedAt() == null) {
			notificationService.create(mentorship.getMentor(), NotificationCategory.MEETING_CHANGE, title, body, MENTOR_HOME);
		}
		if (proposal.getTeamAcceptedAt() == null) {
			for (AppUser responder : teamResponders(mentorship.getOrganization())) {
				notificationService.create(responder, NotificationCategory.MEETING_CHANGE, title, body, TEAM_HOME);
			}
		}
	}

	private void notifyCoordinator(MeetingProposal proposal, String title, String body) {
		appUserRepository.findById(proposal.getProposedBy()).ifPresent(coordinator -> notificationService.create(
				coordinator, NotificationCategory.MEETING_CHANGE, title, body,
				"/coordinator/mentorships?mentorship=" + proposal.getMentorship().getId()));
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
		Mentorship mentorship = proposal.getMentorship();
		if (mentorship.getMentor().getId().equals(actor.getId())) {
			return Side.MENTOR;
		}
		boolean responder = teamResponders(mentorship.getOrganization()).stream()
				.anyMatch(user -> user.getId().equals(actor.getId()));
		return responder ? Side.TEAM : null;
	}

	private Side requireSide(AppUser actor, MeetingProposal proposal) {
		Side side = sideOf(actor, proposal);
		if (side == null) {
			throw new InvalidRequestException("Bu görüşme önerisine yalnızca mentor ve takım yetkilisi yanıt verebilir.");
		}
		return side;
	}

	private MeetingProposal requirePending(UUID proposalId) {
		MeetingProposal proposal = proposalRepository.findById(proposalId)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting proposal not found: " + proposalId));
		if (!proposal.isPending()) {
			throw new ConflictException("Bu görüşme önerisi artık yanıt beklemiyor.");
		}
		return proposal;
	}

	private MeetingProposal requireManagedPending(UUID actorId, UUID proposalId) {
		MeetingProposal proposal = requirePending(proposalId);
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
		Mentorship mentorship = proposal.getMentorship();
		return mentorship.getMentor().getFullName() + " ↔ " + mentorship.getOrganization().getName();
	}

	private MeetingProposalResponse response(MeetingProposal proposal, Program program, AppUser viewer) {
		Mentorship mentorship = proposal.getMentorship();
		Side side = viewer == null ? null : sideOf(viewer, proposal);
		boolean canRespond = proposal.isPending()
				&& ((side == Side.MENTOR && proposal.getMentorAcceptedAt() == null)
						|| (side == Side.TEAM && proposal.getTeamAcceptedAt() == null));
		return new MeetingProposalResponse(
				proposal.getId(),
				mentorship.getId(),
				proposal.getProgramId(),
				program == null ? null : program.getName(),
				mentorship.getMentor().getId(),
				mentorship.getMentor().getFullName(),
				mentorship.getOrganization().getId(),
				mentorship.getOrganization().getName(),
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
