package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.OpenInterventionRequest;
import com.dps.teknokent.dto.responses.AdminInterventionResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipIntervention;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipInterventionRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/**
 * An admin asks a program coordinator to follow one mentorship, then reads what
 * actually happened.
 */
@Service
@RequiredArgsConstructor
public class AdminInterventionService {

	private static final int BODY_LIMIT = 500;

	private final DomainLookupService domainLookupService;
	private final MentorshipRepository mentorshipRepository;
	private final MentorshipInterventionRepository interventionRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final MeetingRepository meetingRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public List<AdminInterventionResponse> list(UUID actorId) {
		requireAdmin(actorId);
		return interventionRepository.findAllByOrderByOpenedAtDesc().stream().map(this::toResponse).toList();
	}

	@Transactional
	public AdminInterventionResponse open(UUID actorId, OpenInterventionRequest request) {
		AppUser admin = requireAdmin(actorId);
		Mentorship mentorship = requireMentorship(request.mentorshipId());
		if (interventionRepository.findByMentorshipIdAndResolvedAtIsNull(mentorship.getId()).isPresent()) {
			throw new InvalidRequestException("Bu mentorluk zaten takipte.");
		}
		Program program = programOf(mentorship.getOrganization());
		AppUser coordinator = domainLookupService.requireUser(program.getCreatedBy());
		String action = request.action().trim();
		String note = TextUtils.normalizeOptional(request.note());
		String pair = mentorship.getMentor().getFullName() + " ↔ " + mentorship.getOrganization().getName();
		String body = program.getName() + " · " + pair + ". " + action;
		if (note != null) {
			body = body + " " + note;
		}
		if (body.length() > BODY_LIMIT) {
			body = body.substring(0, BODY_LIMIT);
		}
		Notification notification = notificationService.create(
				coordinator,
				Texts.get(coordinator, "notification.interventionNeeded.title"),
				body,
				"/coordinator/mentorships?mentorship=" + mentorship.getId());

		MentorshipIntervention row = new MentorshipIntervention();
		row.setId(UUID.randomUUID());
		row.setMentorshipId(mentorship.getId());
		row.setCoordinatorId(coordinator.getId());
		row.setOpenedBy(admin.getId());
		row.setNotificationId(notification.getId());
		row.setAction(action);
		row.setNote(note);
		row.setOpenedAt(Instant.now());
		interventionRepository.save(row);
		return toResponse(row);
	}

	@Transactional
	public AdminInterventionResponse resolve(UUID actorId, UUID interventionId) {
		requireAdmin(actorId);
		MentorshipIntervention row = interventionRepository.findById(interventionId)
				.orElseThrow(() -> new ResourceNotFoundException("Intervention not found: " + interventionId));
		if (row.getResolvedAt() != null) {
			throw new InvalidRequestException("Bu takip zaten kapanmış.");
		}
		row.setResolvedAt(Instant.now());
		interventionRepository.save(row);
		return toResponse(row);
	}

	private Program programOf(Organization organization) {
		if (organization.getActiveProgramId() != null) {
			return programRepository.findById(organization.getActiveProgramId())
					.orElseThrow(() -> new InvalidRequestException("Bu takımın programı bulunamadı."));
		}
		List<ProgramStartup> memberships = programStartupRepository
				.findByOrganizationIdOrderByCreatedAtAsc(organization.getId());
		if (memberships.isEmpty()) {
			throw new InvalidRequestException("Bu takım bir programa bağlı değil.");
		}
		UUID programId = memberships.get(memberships.size() - 1).getProgramId();
		return programRepository.findById(programId)
				.orElseThrow(() -> new InvalidRequestException("Bu takımın programı bulunamadı."));
	}

	private AdminInterventionResponse toResponse(MentorshipIntervention row) {
		AppUser coordinator = domainLookupService.requireUser(row.getCoordinatorId());
		AppUser opener = domainLookupService.requireUser(row.getOpenedBy());
		Notification notification = row.getNotificationId() == null
				? null
				: notificationRepository.findById(row.getNotificationId()).orElse(null);
		Mentorship mentorship = requireMentorship(row.getMentorshipId());
		Instant planned = meetingRepository
				.findByMentorIdAndOrganizationIdOrderByScheduledAtDesc(
						mentorship.getMentor().getId(), mentorship.getOrganization().getId())
				.stream()
				.filter(meeting -> meeting.getStatus() == MeetingStatus.SCHEDULED)
				.map(Meeting::getScheduledAt)
				.filter(at -> at != null && !at.isBefore(row.getOpenedAt()))
				.min(Comparator.naturalOrder())
				.orElse(null);
		return new AdminInterventionResponse(
				row.getId(),
				row.getMentorshipId(),
				coordinator.getId(),
				coordinator.getFullName(),
				opener.getFullName(),
				row.getAction(),
				row.getNote(),
				row.getOpenedAt(),
				notification == null ? null : notification.getReadAt(),
				planned,
				row.getResolvedAt());
	}

	private Mentorship requireMentorship(UUID id) {
		return mentorshipRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + id));
	}

	private AppUser requireAdmin(UUID actorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.ADMIN) {
			throw new InvalidRequestException("Only an admin can follow up a mentorship.");
		}
		return actor;
	}
}
