package com.dps.teknokent.service;

import java.util.Collection;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ShareDocumentRequest;
import com.dps.teknokent.dto.responses.DocumentDownloadResponse;
import com.dps.teknokent.dto.responses.DocumentShareResponse;
import com.dps.teknokent.dto.responses.OrganizationDocumentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.StoredDocumentShare;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.StoredDocumentShareRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A startup's documents as a whole — the Dosyalar tab — as opposed to
 * {@link DocumentService}, which validates and stores a single upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StartupDocumentService {

	private final StoredDocumentRepository storedDocumentRepository;
	private final StoredDocumentShareRepository storedDocumentShareRepository;
	private final AppUserRepository appUserRepository;
	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final DomainLookupService domainLookupService;
	private final ObjectStorage objectStorage;

	@Transactional(readOnly = true)
	public List<OrganizationDocumentResponse> listForOrganization(UUID organizationId, UUID actorId) {
		domainLookupService.requireOrganization(organizationId);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!isTeamMember(actor, organizationId) && !isStaff(actor) && !isMentorForOrganization(actor, organizationId)) {
			throw new InvalidRequestException("Only the startup's team or related mentor can list its documents");
		}
		return describe(storedDocumentRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
	}

	@Transactional(readOnly = true)
	public DocumentDownloadResponse downloadUrl(UUID documentId, UUID actorId) {
		StoredDocument document = requireDocument(documentId);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!canRead(document, actor)) {
			throw new InvalidRequestException("You cannot open this document");
		}
		return new DocumentDownloadResponse(
				objectStorage.presignGet(document.getS3Key()), document.getOriginalFilename(), document.getContentType());
	}

	@Transactional
	public OrganizationDocumentResponse share(UUID documentId, ShareDocumentRequest request) {
		StoredDocument document = requireDocument(documentId);
		AppUser actor = domainLookupService.requireUser(request.actorId());
		requireTeamMember(actor, document);
		AppUser mentor = domainLookupService.requireUser(request.mentorId());
		if (!isMentorOf(mentor, document.getOrganizationId())) {
			throw new InvalidRequestException("A document can only be shared with a mentor of this startup");
		}

		if (!storedDocumentShareRepository.existsByDocumentIdAndMentorId(documentId, mentor.getId())) {
			StoredDocumentShare share = new StoredDocumentShare();
			share.setDocumentId(documentId);
			share.setMentor(mentor);
			share.setSharedBy(actor);
			storedDocumentShareRepository.saveAndFlush(share);
		}
		return describe(List.of(document)).getFirst();
	}

	@Transactional
	public OrganizationDocumentResponse unshare(UUID documentId, UUID mentorId, UUID actorId) {
		StoredDocument document = requireDocument(documentId);
		requireTeamMember(domainLookupService.requireUser(actorId), document);

		storedDocumentShareRepository.deleteByDocumentIdAndMentorId(documentId, mentorId);
		return describe(List.of(document)).getFirst();
	}

	// Staff, or a mentor who can open the file. The startup cannot mark its own files
	// reviewed, and nobody can review a file they uploaded.
	@Transactional
	public OrganizationDocumentResponse review(UUID documentId, UUID actorId) {
		StoredDocument document = requireDocument(documentId);
		AppUser actor = domainLookupService.requireUser(actorId);
		boolean reviewerRole = isStaff(actor) || actor.getRole() == UserRole.MENTOR;
		boolean ownUpload = actor.getId().equals(document.getUserId());
		if (!reviewerRole || ownUpload || !canRead(document, actor)) {
			throw new InvalidRequestException("Only a mentor with access or a coordinator can review this document");
		}

		document.markReviewed(actor.getId(), Instant.now());
		storedDocumentRepository.saveAndFlush(document);
		return describe(List.of(document)).getFirst();
	}

	// The uploader or the founder; the stored object goes after the row, so a
	// storage hiccup leaves an orphaned object rather than a dangling record.
	@Transactional
	public void delete(UUID documentId, UUID actorId) {
		StoredDocument document = requireDocument(documentId);
		AppUser actor = domainLookupService.requireUser(actorId);
		if (!actor.getId().equals(document.getUserId()) && !isFounder(actor, document.getOrganizationId())) {
			throw new InvalidRequestException("Only the uploader or the founder can delete this document");
		}

		storedDocumentShareRepository.deleteByDocumentId(documentId);
		storedDocumentRepository.delete(document);
		try {
			objectStorage.delete(document.getS3Key());
		} catch (RuntimeException error) {
			log.warn("Deleted document {} but its stored object {} remains: {}",
					documentId, document.getS3Key(), error.getMessage());
		}
	}

	private List<OrganizationDocumentResponse> describe(List<StoredDocument> documents) {
		Set<UUID> userIds = new HashSet<>(ids(documents, StoredDocument::getUserId));
		userIds.addAll(ids(documents, StoredDocument::getReviewedById));
		Map<UUID, AppUser> users = byId(appUserRepository.findAllById(userIds), AppUser::getId);
		Map<UUID, Meeting> meetings = byId(meetingRepository.findAllById(ids(documents, StoredDocument::getMeetingId)),
				Meeting::getId);
		Map<UUID, List<DocumentShareResponse>> shares = storedDocumentShareRepository
				.findByDocumentIdIn(ids(documents, StoredDocument::getId)).stream()
				.collect(Collectors.groupingBy(StoredDocumentShare::getDocumentId, Collectors.mapping(
						share -> new DocumentShareResponse(
								share.getMentor().getId(), share.getMentor().getFullName(), share.getSharedAt()),
						Collectors.toList())));

		return documents.stream().map(document -> {
			AppUser uploader = users.get(document.getUserId());
			AppUser reviewer = users.get(document.getReviewedById());
			Meeting meeting = meetings.get(document.getMeetingId());
			return new OrganizationDocumentResponse(
					document.getId(), document.getKind(), document.getOriginalFilename(), document.getContentType(),
					document.getSizeBytes(), document.getCreatedAt(), document.getUserId(),
					uploader == null ? null : uploader.getFullName(), document.getMeetingId(),
					meeting == null ? null : meeting.getAgenda(), meeting == null ? null : meeting.getScheduledAt(),
					document.getReviewedAt(), document.getReviewedById(),
					reviewer == null ? null : reviewer.getFullName(),
					shares.getOrDefault(document.getId(), List.of()));
		}).toList();
	}

	private boolean canRead(StoredDocument document, AppUser actor) {
		if (actor.getId().equals(document.getUserId()) || isStaff(actor)
				|| isTeamMember(actor, document.getOrganizationId())) {
			return true;
		}
		if (actor.getRole() != UserRole.MENTOR) {
			return false;
		}
		if (isMentorForOrganization(actor, document.getOrganizationId())) {
			return true;
		}
		if (storedDocumentShareRepository.existsByDocumentIdAndMentorId(document.getId(), actor.getId())) {
			return true;
		}
		return document.getMeetingId() != null && meetingRepository.findById(document.getMeetingId())
				.map(meeting -> meeting.getMentor() != null && actor.getId().equals(meeting.getMentor().getId()))
				.orElse(false);
	}

	private void requireTeamMember(AppUser actor, StoredDocument document) {
		if (!isTeamMember(actor, document.getOrganizationId())) {
			throw new InvalidRequestException("Only the startup's team can change who sees this document");
		}
	}

	private boolean isMentorOf(AppUser mentor, UUID organizationId) {
		return mentor.getRole() == UserRole.MENTOR && organizationId != null && mentorshipRequestRepository
				.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
						mentor.getId(), organizationId, MentorshipRequestStatus.ACCEPTED)
				.isPresent();
	}

	private boolean isMentorForOrganization(AppUser mentor, UUID organizationId) {
		if (mentor.getRole() != UserRole.MENTOR || organizationId == null) {
			return false;
		}
		if (mentorshipRequestRepository.existsByMentorIdAndOrganizationId(mentor.getId(), organizationId)) {
			return true;
		}
		return programMentorRepository.countAcceptedForOrganization(
				mentor.getId(), organizationId, ProgramMentorStatus.ACCEPTED) > 0;
	}

	private boolean isFounder(AppUser actor, UUID organizationId) {
		if (organizationId == null) {
			return false;
		}
		Organization organization = domainLookupService.requireOrganization(organizationId);
		return organization.getFounder() != null && actor.getId().equals(organization.getFounder().getId());
	}

	private static boolean isTeamMember(AppUser actor, UUID organizationId) {
		return organizationId != null && actor.getOrganization() != null
				&& organizationId.equals(actor.getOrganization().getId());
	}

	private static boolean isStaff(AppUser actor) {
		return actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.COORDINATOR;
	}

	private StoredDocument requireDocument(UUID documentId) {
		return storedDocumentRepository.findById(documentId)
				.orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
	}

	private static Set<UUID> ids(Collection<StoredDocument> documents, Function<StoredDocument, UUID> id) {
		return documents.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
		return items.stream().collect(Collectors.toMap(id, Function.identity()));
	}
}
