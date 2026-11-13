package com.dps.teknokent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.MeetingProposalStatus;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserDeletionCleanupKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingProposal;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.StudentInterest;
import com.dps.teknokent.entity.UserDeletionAudit;
import com.dps.teknokent.entity.UserDeletionCleanup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.UserDeletionAuditRepository;
import com.dps.teknokent.repository.UserDeletionCleanupRepository;
import com.dps.teknokent.util.Texts;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

/**
 * The database half of an admin's user deletion; the caller holds the transaction and the lock on the account.
 * Three rules decide every table:
 * <ul>
 * <li>what belongs to the person alone is deleted (profile, files, notifications, progress, sign-in links);</li>
 * <li>what other people share with them stays, pointing at the account row, which becomes a tombstone named
 * {@link AppUser#DELETED_FULL_NAME};</li>
 * <li>what is still open with them is closed, so nobody waits for a deleted account.</li>
 * </ul>
 * Written as JPQL rather than SQL so the entity mappings, not a second copy of the table names, decide what is
 * touched. Text people typed themselves (messages, notes) is never rewritten.
 */
@Component
@RequiredArgsConstructor
class UserDataEraser {

	private static final List<MentorshipRequestStatus> OPEN_REQUESTS = List.of(
			MentorshipRequestStatus.PENDING,
			MentorshipRequestStatus.MORE_INFO_REQUESTED,
			MentorshipRequestStatus.COUNTER_PROPOSED);
	private static final List<MentorshipStatus> LIVE_MENTORSHIPS = List.of(
			MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE, MentorshipStatus.PAUSED);
	/** Files about the person, deleted wherever they hang. */
	private static final Set<String> PERSONAL_KINDS = Set.of(
			DocumentKind.CV.value(), DocumentKind.AVATAR.value(), DocumentKind.PROFILE_PDF.value(),
			DocumentKind.PEER_EVIDENCE.value());
	/** Files that belong to a program, whoever uploaded them. */
	private static final Set<String> PROGRAM_KINDS = Set.of(
			DocumentKind.SYLLABUS.value(), DocumentKind.WEEKLY_PROGRAM.value());
	/** A shorter name would also match ordinary words inside other people's notifications. */
	private static final int MIN_NAME_LENGTH_TO_REPLACE = 5;
	/** The widths of notification.title and notification.body. */
	private static final int TITLE_LENGTH = 160;
	private static final int BODY_LENGTH = 500;

	@PersistenceContext
	private EntityManager entityManager;

	private final AppUserRepository appUserRepository;
	private final TaskHandoverService taskHandoverService;
	private final NotificationService notificationService;
	private final UserDeletionAuditRepository auditRepository;
	private final UserDeletionCleanupRepository cleanupRepository;

	/**
	 * Erases {@code target} and returns the audit row's id. {@code firebaseUids} are the logins to remove once this
	 * transaction has committed; the stored files to remove are found here.
	 */
	UUID erase(AppUser target, AppUser actor, Set<String> firebaseUids, Instant now) {
		UUID id = target.getId();
		String email = target.getEmail().toLowerCase(Locale.ROOT);
		String fullName = target.getFullName();
		Map<UUID, AppUser> toTell = new LinkedHashMap<>();

		closeOpenRequests(id, toTell);
		List<UUID> archived = leaveOrganizations(target, now);
		cancelLiveWork(id, archived, now, toTell);
		entityManager.flush();

		List<String> storageKeys = deleteDocuments(id, archived);
		deleteOwnData(id, email, archived, firebaseUids);
		stripFromSharedRecords(id, email, fullName);

		target.erase(now);
		appUserRepository.saveAndFlush(target);

		toTell.remove(id);
		for (AppUser person : toTell.values()) {
			if (person.getStatus() == UserAccountStatus.APPROVED) {
				notificationService.create(person,
						Texts.get(person, "notification.accountDeleted.title"),
						Texts.get(person, "notification.accountDeleted.body"),
						person.has(UserRole.MENTOR) ? "/mentor-dashboard" : "/mentorships");
			}
		}

		UserDeletionAudit audit = auditRepository.save(new UserDeletionAudit(id, actor.getId(), now));
		List<UserDeletionCleanup> jobs = new ArrayList<>();
		firebaseUids.forEach(uid -> jobs.add(
				new UserDeletionCleanup(audit.getId(), UserDeletionCleanupKind.FIREBASE_LOGIN, uid, now)));
		storageKeys.forEach(key -> jobs.add(
				new UserDeletionCleanup(audit.getId(), UserDeletionCleanupKind.STORED_OBJECT, key, now)));
		if (jobs.isEmpty()) {
			audit.setCleanupCompletedAt(now);
		}
		cleanupRepository.saveAll(jobs);
		entityManager.flush();
		// The bulk statements above went around the persistence context; nothing read before them is trusted.
		entityManager.clear();
		return audit.getId();
	}

	/** A request the person sent is withdrawn; one sent to them as mentor is declined. */
	private void closeOpenRequests(UUID id, Map<UUID, AppUser> toTell) {
		List<MentorshipRequest> open = entityManager.createQuery(
				"select r from MentorshipRequest r where (r.requester.id = :id or r.mentor.id = :id) and r.status in :open",
				MentorshipRequest.class)
				.setParameter("id", id)
				.setParameter("open", OPEN_REQUESTS)
				.getResultList();
		for (MentorshipRequest request : open) {
			if (request.getMentor().getId().equals(id)) {
				request.recordDecision(MentorshipRequestStatus.DECLINED,
						Texts.get(request.getRequester(), "text.accountDeleted.requestDeclined"));
				tell(toTell, request.getRequester());
			} else {
				request.withdraw();
				tell(toTell, request.getMentor());
			}
		}
	}

	/**
	 * Takes the person out of their startup and out of any startup they founded. A startup left with nobody is
	 * archived (kept for the mentors' history, no longer a team); otherwise the longest-standing member leads it.
	 * Returns the archived startups.
	 */
	private List<UUID> leaveOrganizations(AppUser target, Instant now) {
		UUID id = target.getId();
		Map<UUID, Organization> organizations = new LinkedHashMap<>();
		if (target.getOrganization() != null) {
			organizations.put(target.getOrganization().getId(), target.getOrganization());
		}
		entityManager.createQuery("select o from Organization o where o.founder.id = :id", Organization.class)
				.setParameter("id", id)
				.getResultList()
				.forEach(organization -> organizations.putIfAbsent(organization.getId(), organization));

		List<UUID> archived = new ArrayList<>();
		for (Organization organization : organizations.values()) {
			List<AppUser> others = appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()).stream()
					.filter(member -> !member.getId().equals(id))
					.toList();
			boolean wasFounder = organization.getFounder() != null && organization.getFounder().getId().equals(id);
			if (others.isEmpty()) {
				if (organization.getArchivedAt() == null) {
					organization.setArchivedAt(now);
				}
				organization.setInviteCode(null);
				organization.setFounder(null);
				if (organization.isPersonal()) {
					// The hidden team of one is named after its student (PersonalTeamService).
					organization.setName(AppUser.DELETED_FULL_NAME);
				}
				archived.add(organization.getId());
				continue;
			}
			taskHandoverService.markLeft(target, organization);
			if (wasFounder) {
				// Only an approved member can lead; with none the team stays without a leader until one is.
				List<AppUser> approved = others.stream()
						.filter(member -> member.getStatus() == UserAccountStatus.APPROVED)
						.toList();
				AppUser successor = approved.isEmpty() ? null : longestStanding(organization.getId(), approved);
				organization.setFounder(successor);
				if (successor != null) {
					notificationService.create(successor,
							Texts.get(successor, "notification.teamLeaderAfterDeletion.title"),
							Texts.get(successor, "notification.teamLeaderAfterDeletion.body", organization.getName()),
							"/startup/team");
				}
			}
		}
		entityManager.createQuery(
				"update TeamMembership m set m.leftAt = :now, m.exitReason = :reason where m.userId = :id and m.leftAt is null")
				.setParameter("now", now)
				.setParameter("reason", TeamExitReason.REMOVED)
				.setParameter("id", id)
				.executeUpdate();
		entityManager.createQuery(
				"update TeamChangeRequest r set r.status = :cancelled, r.decidedAt = :now "
						+ "where r.status = :pending and (r.member.id = :id or r.requestedBy.id = :id)")
				.setParameter("cancelled", TeamChangeStatus.CANCELLED)
				.setParameter("pending", TeamChangeStatus.PENDING)
				.setParameter("now", now)
				.setParameter("id", id)
				.executeUpdate();
		return archived;
	}

	private AppUser longestStanding(UUID organizationId, List<AppUser> others) {
		List<UUID> byJoinDate = entityManager.createQuery(
				"select m.userId from TeamMembership m where m.organization.id = :organizationId and m.leftAt is null "
						+ "order by m.joinedAt asc",
				UUID.class)
				.setParameter("organizationId", organizationId)
				.getResultList();
		for (UUID memberId : byJoinDate) {
			for (AppUser member : others) {
				if (member.getId().equals(memberId)) {
					return member;
				}
			}
		}
		// Members from before team history was recorded (V81) have no row; the oldest account then.
		return others.stream()
				.min((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
				.orElseThrow();
	}

	/**
	 * Future meetings, waiting meeting proposals and running mentorships end when the person was their mentor, or
	 * when they belong to a startup this deletion archived.
	 */
	private void cancelLiveWork(UUID id, List<UUID> archived, Instant now, Map<UUID, AppUser> toTell) {
		List<Meeting> meetings = new ArrayList<>(entityManager.createQuery(
				"select m from Meeting m where m.status = :scheduled and m.scheduledAt > :now and m.mentor.id = :id",
				Meeting.class)
				.setParameter("scheduled", MeetingStatus.SCHEDULED)
				.setParameter("now", now)
				.setParameter("id", id)
				.getResultList());
		List<MeetingProposal> proposals = new ArrayList<>(entityManager.createQuery(
				"select p from MeetingProposal p where p.status = :pending and p.mentor.id = :id", MeetingProposal.class)
				.setParameter("pending", MeetingProposalStatus.PENDING)
				.setParameter("id", id)
				.getResultList());
		List<Mentorship> mentorships = new ArrayList<>(entityManager.createQuery(
				"select m from Mentorship m where m.status in :live and m.mentor.id = :id", Mentorship.class)
				.setParameter("live", LIVE_MENTORSHIPS)
				.setParameter("id", id)
				.getResultList());
		if (!archived.isEmpty()) {
			meetings.addAll(entityManager.createQuery(
					"select m from Meeting m where m.status = :scheduled and m.scheduledAt > :now "
							+ "and m.organization.id in :archived and (m.mentor is null or m.mentor.id <> :id)",
					Meeting.class)
					.setParameter("scheduled", MeetingStatus.SCHEDULED)
					.setParameter("now", now)
					.setParameter("archived", archived)
					.setParameter("id", id)
					.getResultList());
			proposals.addAll(entityManager.createQuery(
					"select p from MeetingProposal p where p.status = :pending and p.organization.id in :archived "
							+ "and (p.mentor is null or p.mentor.id <> :id)",
					MeetingProposal.class)
					.setParameter("pending", MeetingProposalStatus.PENDING)
					.setParameter("archived", archived)
					.setParameter("id", id)
					.getResultList());
			mentorships.addAll(entityManager.createQuery(
					"select m from Mentorship m where m.status in :live and m.organization.id in :archived "
							+ "and m.mentor.id <> :id",
					Mentorship.class)
					.setParameter("live", LIVE_MENTORSHIPS)
					.setParameter("archived", archived)
					.setParameter("id", id)
					.getResultList());
		}

		Set<UUID> teams = new LinkedHashSet<>();
		for (Meeting meeting : meetings) {
			meeting.setStatus(MeetingStatus.CANCELLED);
			tell(toTell, meeting.getMentor());
			teams.add(meeting.getOrganization().getId());
		}
		for (MeetingProposal proposal : proposals) {
			proposal.setStatus(MeetingProposalStatus.WITHDRAWN);
			tell(toTell, proposal.getMentor());
			teams.add(proposal.getOrganization().getId());
		}
		for (Mentorship mentorship : mentorships) {
			mentorship.setStatus(MentorshipStatus.CANCELLED);
			tell(toTell, mentorship.getMentor());
			teams.add(mentorship.getOrganization().getId());
		}
		teams.removeAll(archived);
		if (!teams.isEmpty()) {
			appUserRepository.findByOrganizationIdInOrderByFullNameAsc(teams).forEach(member -> tell(toTell, member));
		}
	}

	private static void tell(Map<UUID, AppUser> toTell, AppUser person) {
		if (person != null) {
			toTell.putIfAbsent(person.getId(), person);
		}
	}

	/** Deletes the rows of the files that go and returns their storage keys, to be removed after the commit. */
	private List<String> deleteDocuments(UUID id, List<UUID> archived) {
		List<StoredDocument> candidates = new ArrayList<>(entityManager.createQuery(
				"select d from StoredDocument d where d.userId = :id", StoredDocument.class)
				.setParameter("id", id)
				.getResultList());
		if (!archived.isEmpty()) {
			candidates.addAll(entityManager.createQuery(
					"select d from StoredDocument d where d.organizationId in :archived "
							+ "and (d.userId is null or d.userId <> :id)",
					StoredDocument.class)
					.setParameter("archived", archived)
					.setParameter("id", id)
					.getResultList());
		}
		List<StoredDocument> going = candidates.stream().filter(document -> goes(document, id, archived)).toList();
		if (going.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = going.stream().map(StoredDocument::getId).toList();
		update("delete from StoredDocumentShare s where s.documentId in :ids", "ids", ids);
		update("delete from DocumentRequestUpload u where u.storedDocument.id in :ids", "ids", ids);
		update("update ActionItem a set a.submissionDocumentId = null where a.submissionDocumentId in :ids", "ids", ids);
		update("delete from StoredDocument d where d.id in :ids", "ids", ids);
		return going.stream().map(StoredDocument::getS3Key).toList();
	}

	/**
	 * A file about the person always goes. A program's file and a meeting's file stay (other people use them). Of
	 * the rest, the person's own unattached files go, and so does everything of a startup this deletion archived;
	 * files of a startup that goes on stay with its team.
	 */
	private static boolean goes(StoredDocument document, UUID id, Collection<UUID> archived) {
		boolean own = id.equals(document.getUserId());
		if (PERSONAL_KINDS.contains(document.getKind())) {
			return own;
		}
		if (PROGRAM_KINDS.contains(document.getKind()) || document.getMeetingId() != null) {
			return false;
		}
		if (document.getOrganizationId() == null) {
			return own;
		}
		return archived.contains(document.getOrganizationId());
	}

	private void deleteOwnData(UUID id, String email, List<UUID> archived, Set<String> firebaseUids) {
		// Sign-in links first: with these gone no institution login can be matched to the row again. Only OTG links
		// reach this point; UserDeletionService refuses accounts with an ODTÜ roster record or login.
		update("delete from AppUserIdentity i where i.user.id = :id", "id", id);
		if (!firebaseUids.isEmpty()) {
			update("delete from OnboardingDraft d where d.firebaseUid in :uids", "uids", firebaseUids);
			update("delete from DataProcessingConsent c where c.consenterFirebaseUid in :uids", "uids", firebaseUids);
		}
		// The consent records name the person, so they go with the account, like the profile they were given for.
		update("delete from DataProcessingConsent c where c.consenterUserId = :id or c.subjectUserId = :id", "id", id);

		update("delete from MentorAvailabilitySlot s where s.mentor.id = :id", "id", id);
		update("delete from MentorAvailabilityException e where e.mentor.id = :id", "id", id);
		update("delete from MentorAvailabilityRule r where r.mentor.id = :id", "id", id);
		// Through the entity, so its collection tables go with it.
		MentorProfile mentorProfile = entityManager.find(MentorProfile.class, id);
		if (mentorProfile != null) {
			entityManager.remove(mentorProfile);
		}
		StudentInterest interest = entityManager.find(StudentInterest.class, id);
		if (interest != null) {
			entityManager.remove(interest);
		}
		entityManager.flush();

		update("delete from ProfileAnalysis a where a.appUserId = :id", "id", id);
		if (!archived.isEmpty()) {
			update("delete from ProfileAnalysis a where a.organizationId in :archived", "archived", archived);
		}

		update("update MentorshipIntervention i set i.notificationId = null where i.notificationId in "
				+ "(select n.id from Notification n where n.recipient.id = :id)", "id", id);
		update("delete from Notification n where n.recipient.id = :id", "id", id);
		update("delete from PushToken t where t.user.id = :id", "id", id);
		update("delete from NotificationPreference p where p.userId = :id", "id", id);

		update("delete from TrainingProgress p where p.userId = :id", "id", id);
		update("delete from TrainingAttempt a where a.userId = :id", "id", id);
		update("delete from UserBadge b where b.userId = :id", "id", id);
		update("delete from ProgramAttendance a where a.userId = :id", "id", id);
		update("delete from PeerMentorDraft d where d.userId = :id", "id", id);
		update("delete from PlatformFeedback f where f.author.id = :id", "id", id);

		// Files other people shared with the person as a mentor: the share is theirs to lose, the file is not.
		update("delete from StoredDocumentShare s where s.mentor.id = :id", "id", id);

		update("delete from ProgramMentor m where m.mentorId = :id", "id", id);
		update("delete from ProgramCoordinator c where c.userId = :id", "id", id);
		entityManager.createQuery("delete from ProgramMentorInvite i where i.mentorId = :id or lower(i.email) = :email")
				.setParameter("id", id)
				.setParameter("email", email)
				.executeUpdate();
		update("delete from ProgramTeamInvite i where lower(i.leadEmail) = :email and i.joinedAt is null", "email", email);
		update("delete from OrganizationPendingMember m where lower(m.email) = :email", "email", email);
	}

	/** Copies of the person's name and address that live inside records other people keep. */
	private void stripFromSharedRecords(UUID id, String email, String fullName) {
		// A recommendation keeps a copy of the mentor's profile as it was shown; the row and its rank stay.
		List<MentorshipRequestRecommendationItem> recommendations = entityManager.createQuery(
				"select i from MentorshipRequestRecommendationItem i where i.mentor.id = :id",
				MentorshipRequestRecommendationItem.class)
				.setParameter("id", id)
				.getResultList();
		for (MentorshipRequestRecommendationItem item : recommendations) {
			item.setMentorName(AppUser.DELETED_FULL_NAME);
			item.setProfessionalTitle("");
			item.setCompany("");
			item.getReasons().clear();
			item.getEvidence().clear();
		}
		entityManager.createQuery(
				"update MentorshipRequest r set r.requestedForName = :deleted "
						+ "where r.requester.id = :id and lower(r.requestedForName) = :name")
				.setParameter("deleted", AppUser.DELETED_FULL_NAME)
				.setParameter("id", id)
				.setParameter("name", fullName.trim().toLowerCase(Locale.ROOT))
				.executeUpdate();
		// A team that joined its program through the person's address keeps the invite row, without the address.
		entityManager.createQuery(
				"update ProgramTeamInvite i set i.leadEmail = :placeholder where lower(i.leadEmail) = :email")
				.setParameter("placeholder", AppUser.deletedEmailFor(id))
				.setParameter("email", email)
				.executeUpdate();

		String name = fullName.trim();
		if (name.length() < MIN_NAME_LENGTH_TO_REPLACE || nameIsShared(id, name)) {
			return;
		}
		// The text of a notification is all there is: it carries no reference to the person it names. So the name
		// is replaced where it stands as whole words, row by row (the replacement can be longer than the column).
		Pattern wholeName = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])");
		List<Notification> naming = entityManager.createQuery(
				"select n from Notification n where n.recipient.id <> :id "
						+ "and (locate(:name, n.title) > 0 or locate(:name, n.body) > 0)",
				Notification.class)
				.setParameter("name", name)
				.setParameter("id", id)
				.getResultList();
		for (Notification notification : naming) {
			notification.setTitle(fit(replaceName(wholeName, notification.getTitle()), TITLE_LENGTH));
			notification.setBody(fit(replaceName(wholeName, notification.getBody()), BODY_LENGTH));
		}
	}

	/**
	 * Two people with the same name cannot be told apart inside a notification's text, and rewriting it would
	 * change what was said about the one who is still here. Then the text is left as it is.
	 */
	private boolean nameIsShared(UUID id, String name) {
		return entityManager.createQuery(
				"select count(u) from AppUser u where u.id <> :id and lower(u.fullName) = :name", Long.class)
				.setParameter("id", id)
				.setParameter("name", name.toLowerCase(Locale.ROOT))
				.getSingleResult() > 0;
	}

	private static String replaceName(Pattern wholeName, String text) {
		return wholeName.matcher(text).replaceAll(Matcher.quoteReplacement(AppUser.DELETED_FULL_NAME));
	}

	private static String fit(String text, int length) {
		return text.length() <= length ? text : text.substring(0, length);
	}

	private void update(String jpql, String parameter, Object value) {
		entityManager.createQuery(jpql).setParameter(parameter, value).executeUpdate();
	}
}
