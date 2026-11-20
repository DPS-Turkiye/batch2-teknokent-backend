package com.dps.teknokent.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserDeletionCleanupKind;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingProposal;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.UserDeletionAudit;
import com.dps.teknokent.entity.UserDeletionCleanup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.repository.UserDeletionCleanupRepository;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The database half of a founder deleting their startup; the caller holds the transaction and the lock on the
 * startup. The same three rules as {@link UserDataEraser}:
 * <ul>
 * <li>what the startup kept for itself is deleted (its profile, files, Süreç records, program places, invites);</li>
 * <li>what it shares with mentors and coordinators stays, pointing at the startup row, which becomes a tombstone
 * named {@link Organization#DELETED_NAME};</li>
 * <li>what is still open with it is closed (requests, mentorships, upcoming meetings).</li>
 * </ul>
 * The members' accounts stay; they leave the team, as they would by leaving it themselves. Text people typed
 * (messages, notes, tasks they share with a mentor) is never rewritten.
 */
@Component
@RequiredArgsConstructor
class OrganizationDataEraser {

	private static final List<MentorshipRequestStatus> OPEN_REQUESTS = List.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED);
	private static final List<MentorshipStatus> LIVE_MENTORSHIPS = List.of(
			MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE, MentorshipStatus.PAUSED);

	private final EntityManager entityManager;
	private final AppUserRepository appUserRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final NotificationService notificationService;
	private final UserDeletionAuditRepository auditRepository;
	private final UserDeletionCleanupRepository cleanupRepository;

	/** Erases {@code startup} and returns the audit row's id; the stored files to remove are queued on it. */
	UUID erase(Organization startup, AppUser founder, Instant now) {
		UUID id = startup.getId();
		String name = startup.getName();
		List<ProgramStartup> places = programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(id);
		@Nullable
		UUID program = programOf(startup, places);
		Map<UUID, AppUser> mentors = new LinkedHashMap<>();
		// Members are locked before the requests and mentorships, the same order a member's own deletion takes them.
		List<AppUser> team = appUserRepository.findByOrganizationIdForUpdate(id);

		closeOpenWork(id, now, mentors);
		List<AppUser> members = dissolveTeam(id, team, founder, program, now);
		entityManager.flush();

		List<String> storageKeys = deleteOwnData(id);
		anonymizeSnapshots(id);
		startup.erase(now);
		entityManager.flush();

		notifyPeople(name, founder, members, mentors, places);

		UserDeletionAudit audit = auditRepository.save(UserDeletionAudit.ofOrganization(id, founder.getId(), now));
		List<UserDeletionCleanup> jobs = storageKeys.stream()
				.map(key -> new UserDeletionCleanup(audit.getId(), UserDeletionCleanupKind.STORED_OBJECT, key, now))
				.toList();
		if (jobs.isEmpty()) {
			audit.setCleanupCompletedAt(now);
		}
		cleanupRepository.saveAll(jobs);
		entityManager.flush();
		// The bulk statements above went around the persistence context; nothing read before them is trusted.
		entityManager.clear();
		return audit.getId();
	}

	/** Requests are withdrawn; running mentorships, upcoming meetings and waiting proposals end. */
	private void closeOpenWork(UUID id, Instant now, Map<UUID, AppUser> mentors) {
		entityManager.createQuery(
				"select r from MentorshipRequest r where r.organization.id = :id and r.status in :open",
				MentorshipRequest.class)
				.setParameter("id", id)
				.setParameter("open", OPEN_REQUESTS)
				.getResultList()
				.forEach(request -> {
					request.withdraw();
					tell(mentors, request.getMentor());
				});
		LocalDate today = LocalDate.ofInstant(now, MeetingSlotTimes.APP_ZONE);
		entityManager.createQuery(
				"select m from Mentorship m where m.organization.id = :id and m.status in :live", Mentorship.class)
				.setParameter("id", id)
				.setParameter("live", LIVE_MENTORSHIPS)
				.getResultList()
				.forEach(mentorship -> {
					mentorship.setStatus(MentorshipStatus.CANCELLED);
					if (mentorship.getEndDate() == null || mentorship.getEndDate().isAfter(today)) {
						mentorship.setEndDate(today);
					}
					tell(mentors, mentorship.getMentor());
				});
		entityManager.createQuery(
				"select m from Meeting m where m.organization.id = :id and m.status = :scheduled and m.scheduledAt > :now",
				Meeting.class)
				.setParameter("id", id)
				.setParameter("scheduled", MeetingStatus.SCHEDULED)
				.setParameter("now", now)
				.getResultList()
				.forEach(meeting -> {
					meeting.setStatus(MeetingStatus.CANCELLED);
					tell(mentors, meeting.getMentor());
				});
		entityManager.createQuery(
				"select p from MeetingProposal p where p.organization.id = :id and p.status = :pending",
				MeetingProposal.class)
				.setParameter("id", id)
				.setParameter("pending", MeetingProposalStatus.PENDING)
				.getResultList()
				.forEach(proposal -> {
					proposal.setStatus(MeetingProposalStatus.WITHDRAWN);
					tell(mentors, proposal.getMentor());
				});
	}

	/**
	 * Every member leaves, the founder too. Like a member who leaves on their own, each keeps the startup's program
	 * as their own, so a coordinator can still find them there. Returns the members other than the founder.
	 */
	private List<AppUser> dissolveTeam(UUID id, List<AppUser> team, AppUser founder, @Nullable UUID program,
			Instant now) {
		List<AppUser> others = new ArrayList<>();
		for (AppUser member : team) {
			if (member.getProgramId() == null && program != null) {
				member.setProgramId(program);
			}
			member.setOrganization(null);
			appUserRepository.save(member);
			if (!member.getId().equals(founder.getId())) {
				others.add(member);
			}
		}
		entityManager.createQuery(
				"update TeamMembership m set m.leftAt = :now, m.exitReason = case when m.userId = :founder "
						+ "then :left else :removed end where m.organization.id = :id and m.leftAt is null")
				.setParameter("now", now)
				.setParameter("founder", founder.getId())
				.setParameter("left", TeamExitReason.LEFT)
				.setParameter("removed", TeamExitReason.REMOVED)
				.setParameter("id", id)
				.executeUpdate();
		entityManager.createQuery(
				"update TeamChangeRequest r set r.status = :cancelled, r.decidedAt = :now "
						+ "where r.organization.id = :id and r.status = :pending")
				.setParameter("cancelled", TeamChangeStatus.CANCELLED)
				.setParameter("pending", TeamChangeStatus.PENDING)
				.setParameter("now", now)
				.setParameter("id", id)
				.executeUpdate();
		return others;
	}

	/**
	 * The startup's files, except a member's own (their CV goes with them, not with the team), a program's and one
	 * shared with a mentor in a meeting. Then everything the startup recorded for itself. Returns the storage keys.
	 */
	private List<String> deleteOwnData(UUID id) {
		List<StoredDocument> going = entityManager.createQuery(
				"select d from StoredDocument d where d.organizationId = :id and d.meetingId is null",
				StoredDocument.class)
				.setParameter("id", id)
				.getResultList()
				.stream()
				.filter(document -> !StoredDocumentRows.PERSONAL_KINDS.contains(document.getKind())
						&& !StoredDocumentRows.PROGRAM_KINDS.contains(document.getKind()))
				.toList();
		List<String> storageKeys = StoredDocumentRows.remove(entityManager, going);

		update("delete from DocumentRequestUpload u where u.item.id in "
				+ "(select i.id from DocumentRequestItem i where i.organization.id = :id)", id);
		update("delete from DocumentRequestItem i where i.organization.id = :id", id);
		// A task tied to a mentor's request or meeting is history the mentor shares; the rest was the team's own.
		update("delete from ActionItem a where a.organization.id = :id and a.mentorshipRequest is null "
				+ "and a.meeting is null", id);
		update("update ActionItem a set a.round = null where a.organization.id = :id", id);
		update("delete from StartupRound r where r.organization.id = :id", id);
		update("delete from ProgressUpdate u where u.organization.id = :id", id);
		update("delete from Bottleneck b where b.organization.id = :id", id);
		update("delete from ProfileAnalysis a where a.organizationId = :id", id);
		update("delete from OrganizationPendingMember m where m.organizationId = :id", id);
		update("delete from ProgramStartup s where s.organizationId = :id", id);
		update("delete from ProgramTeamInvite i where i.organizationId = :id and i.joinedAt is null", id);
		entityManager.createQuery("update ProgramTeamInvite i set i.teamName = :deleted where i.organizationId = :id")
				.setParameter("deleted", Organization.DELETED_NAME)
				.setParameter("id", id)
				.executeUpdate();
		return storageKeys;
	}

	/** A recommendation run kept a copy of the startup's profile as it was matched; the mentors' ranks stay. */
	private void anonymizeSnapshots(UUID id) {
		entityManager.createQuery(
				"select r from MentorshipRequestRecommendationRun r where r.request.organization.id = :id",
				MentorshipRequestRecommendationRun.class)
				.setParameter("id", id)
				.getResultList()
				.forEach(run -> {
					run.setStartupName(Organization.DELETED_NAME);
					run.setStartupSector(null);
					run.setStartupStage(null);
					run.setStartupSummary(null);
					run.getStartupTechnologies().clear();
				});
	}

	private void notifyPeople(String name, AppUser founder, List<AppUser> members, Map<UUID, AppUser> mentors,
			List<ProgramStartup> places) {
		for (AppUser member : members) {
			if (member.getStatus() == UserAccountStatus.APPROVED) {
				notificationService.create(member,
						Texts.get(member, "notification.startupDeleted.member.title"),
						Texts.get(member, "notification.startupDeleted.member.body", name),
						"/profile");
			}
		}
		mentors.remove(founder.getId());
		for (AppUser mentor : mentors.values()) {
			if (mentor.getStatus() == UserAccountStatus.APPROVED) {
				notificationService.create(mentor,
						Texts.get(mentor, "notification.startupDeleted.mentor.title"),
						Texts.get(mentor, "notification.startupDeleted.mentor.body", name),
						"/mentor-dashboard");
			}
		}
		List<Program> programs = programRepository.findAllById(
				places.stream().map(ProgramStartup::getProgramId).distinct().toList());
		ProgramCoordination.runners(programs, programCoordinatorRepository, appUserRepository)
				.forEach((programId, coordinators) -> coordinators.forEach(coordinator -> notificationService.create(
						coordinator,
						Texts.get(coordinator, "notification.startupDeleted.coordinator.title"),
						Texts.get(coordinator, "notification.startupDeleted.coordinator.body", name),
						"/coordinator/programs/" + programId)));
	}

	/** The program the startup is in: the one it is active in, else the last it joined. */
	private static @Nullable UUID programOf(Organization startup, List<ProgramStartup> places) {
		if (startup.getActiveProgramId() != null) {
			return startup.getActiveProgramId();
		}
		return places.isEmpty() ? null : places.get(places.size() - 1).getProgramId();
	}

	private static void tell(Map<UUID, AppUser> people, @Nullable AppUser person) {
		if (person != null) {
			people.putIfAbsent(person.getId(), person);
		}
	}

	private void update(String jpql, UUID id) {
		entityManager.createQuery(jpql).setParameter("id", id).executeUpdate();
	}
}
