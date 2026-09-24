package com.dps.teknokent.service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AttachDocumentRequestUploadRequest;
import com.dps.teknokent.dto.responses.DocumentRequestItemResponse;
import com.dps.teknokent.dto.responses.DocumentRequestUploadResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.DocumentRequestUpload;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentRequestService {

	private static final String MENTOR_REQUESTED_TITLE = "Mentor belgeler istedi";

	private final DocumentRequestItemRepository itemRepository;
	private final DocumentRequestUploadRepository uploadRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final MentorshipRepository mentorshipRepository;
	private final ProgramRepository programRepository;
	private final ProgramStartupRepository programStartupRepository;
	private final AppUserRepository appUserRepository;
	private final DomainLookupService domainLookupService;
	private final NotificationService notificationService;

	@Transactional(readOnly = true)
	public List<DocumentRequestItemResponse> list(
			UUID mentorshipRequestId,
			UUID meetingId,
			UUID mentorshipId) {
		assertExactlyOneTarget(mentorshipRequestId, meetingId, mentorshipId);
		List<DocumentRequestItem> items;
		if (mentorshipRequestId != null) {
			items = itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(mentorshipRequestId);
		} else if (meetingId != null) {
			items = itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meetingId);
		} else {
			items = itemRepository.findByMentorshipIdOrderBySortOrderAscCreatedAtAsc(mentorshipId);
		}
		return toResponses(items);
	}

	/** Every document request addressed to a startup, whoever asked — the Dosyalar tab's checklist. */
	@Transactional(readOnly = true)
	public List<DocumentRequestItemResponse> listForOrganization(UUID organizationId, UUID actorId) {
		domainLookupService.requireOrganization(organizationId);
		AppUser actor = domainLookupService.requireUser(actorId);
		boolean teamMember = actor.getOrganization() != null
				&& organizationId.equals(actor.getOrganization().getId());
		boolean staff = actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.COORDINATOR;
		if (!teamMember && !staff) {
			throw new InvalidRequestException("Only the startup's team can list its document requests");
		}
		return toResponses(itemRepository.findByOrganizationIdOrderByCreatedAtDescSortOrderAsc(organizationId));
	}

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnRequest(
			UUID mentorshipRequestId,
			UUID actorId,
			List<String> titles,
			LocalDate dueDate) {
		MentorshipRequest request = domainLookupService.requireMentorshipRequest(mentorshipRequestId);
		AppUser actor = requireMentorActor(actorId, request.getMentor().getId());
		itemRepository.deleteByMentorshipRequestId(request.getId());
		List<DocumentRequestItem> saved = insertItems(
				actor,
				request.getOrganization(),
				normalizeTitles(titles),
				dueDate,
				request,
				null,
				null);
		notifyDocumentsRequested(
				request.getOrganization(), actor, saved, MENTOR_REQUESTED_TITLE, "/mentorships?tab=active");
		return toResponses(saved);
	}

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnMeeting(
			UUID meetingId,
			UUID actorId,
			List<String> titles,
			LocalDate dueDate) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		AppUser actor = requireMeetingMentor(actorId, meeting);
		itemRepository.deleteByMeetingId(meeting.getId());
		List<DocumentRequestItem> saved = insertItems(
				actor,
				meeting.getOrganization(),
				normalizeTitles(titles),
				dueDate,
				null,
				meeting,
				null);
		notifyDocumentsRequested(
				meeting.getOrganization(),
				actor,
				saved,
				MENTOR_REQUESTED_TITLE,
				"/meetings/research?id=" + meeting.getId());
		return toResponses(saved);
	}

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnMentorship(
			UUID mentorshipId,
			UUID actorId,
			List<String> titles,
			LocalDate dueDate) {
		Mentorship mentorship = mentorshipRepository.findById(mentorshipId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + mentorshipId));
		AppUser actor = requireMentorActor(actorId, mentorship.getMentor().getId());
		itemRepository.deleteByMentorshipId(mentorship.getId());
		List<DocumentRequestItem> saved = insertItems(
				actor,
				mentorship.getOrganization(),
				normalizeTitles(titles),
				dueDate,
				null,
				null,
				mentorship);
		notifyDocumentsRequested(
				mentorship.getOrganization(),
				actor,
				saved,
				MENTOR_REQUESTED_TITLE,
				"/mentorships?tab=regular");
		return toResponses(saved);
	}

	/**
	 * The program's coordinator asks every startup in the program for the same documents.
	 * Unlike the other targets this edits the checklist in place: a program reaches many
	 * startups, and a title that stays keeps every upload made against it.
	 */
	@Transactional
	public List<DocumentRequestItemResponse> replaceOnProgram(
			UUID programId,
			UUID actorId,
			List<String> titles,
			LocalDate dueDate) {
		Program program = programRepository.findById(programId)
				.orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.COORDINATOR || !actor.getId().equals(program.getCreatedBy())) {
			throw new InvalidRequestException("Only the program coordinator can request documents from its startups");
		}
		List<String> normalized = normalizeTitles(titles);

		Map<UUID, Map<String, Deque<DocumentRequestItem>>> existing = new HashMap<>();
		for (DocumentRequestItem item : itemRepository.findByProgramIdOrderBySortOrderAscCreatedAtAsc(program.getId())) {
			existing.computeIfAbsent(item.getOrganization().getId(), id -> new HashMap<>())
					.computeIfAbsent(item.getTitle(), title -> new ArrayDeque<>())
					.add(item);
		}

		List<DocumentRequestItem> saved = new ArrayList<>();
		List<DocumentRequestItem> leftovers = new ArrayList<>();
		for (ProgramStartup startup : programStartupRepository.findByProgramIdOrderByCreatedAtAsc(program.getId())) {
			Organization organization = domainLookupService.requireOrganization(startup.getOrganizationId());
			Map<String, Deque<DocumentRequestItem>> current = existing.getOrDefault(organization.getId(), Map.of());
			List<DocumentRequestItem> added = new ArrayList<>();
			for (int order = 0; order < normalized.size(); order++) {
				String title = normalized.get(order);
				Deque<DocumentRequestItem> matches = current.get(title);
				DocumentRequestItem item = matches == null ? null : matches.poll();
				if (item == null) {
					item = newItem(actor, organization, title, order, dueDate);
					item.setProgramId(program.getId());
					added.add(item);
				} else {
					item.setSortOrder(order);
					item.setDueDate(dueDate);
				}
				saved.add(itemRepository.save(item));
			}
			current.values().forEach(leftovers::addAll);
			existing.remove(organization.getId());
			notifyDocumentsRequested(organization, actor, added, "Koordinatör belgeler istedi", "/startup/files");
		}
		// Startups that have left the program no longer owe its documents.
		existing.values().forEach(byTitle -> byTitle.values().forEach(leftovers::addAll));
		if (!leftovers.isEmpty()) {
			itemRepository.deleteAll(leftovers);
		}
		return toResponses(saved);
	}

	@Transactional
	public void saveTitlesOnRequest(MentorshipRequest request, AppUser mentor, List<String> titles) {
		List<String> normalized = normalizeTitles(titles);
		if (normalized.isEmpty()) {
			return;
		}
		itemRepository.deleteByMentorshipRequestId(request.getId());
		List<DocumentRequestItem> saved = insertItems(
				mentor,
				request.getOrganization(),
				normalized,
				null,
				request,
				null,
				null);
		notifyDocumentsRequested(
				request.getOrganization(), mentor, saved, MENTOR_REQUESTED_TITLE, "/mentorships?tab=active");
	}

	@Transactional
	public void saveTitlesOnMeeting(Meeting meeting, AppUser mentor, List<String> titles) {
		List<String> normalized = normalizeTitles(titles);
		if (normalized.isEmpty()) {
			return;
		}
		itemRepository.deleteByMeetingId(meeting.getId());
		List<DocumentRequestItem> saved = insertItems(
				mentor,
				meeting.getOrganization(),
				normalized,
				null,
				null,
				meeting,
				null);
		notifyDocumentsRequested(
				meeting.getOrganization(),
				mentor,
				saved,
				MENTOR_REQUESTED_TITLE,
				"/meetings/research?id=" + meeting.getId());
	}

	@Transactional
	public void copyFromRequestToMeeting(MentorshipRequest request, Meeting meeting) {
		if (request == null || meeting == null) {
			return;
		}
		if (!itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meeting.getId()).isEmpty()) {
			return;
		}
		List<DocumentRequestItem> sourceItems = itemRepository
				.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(request.getId());
		if (sourceItems.isEmpty()) {
			return;
		}
		Map<UUID, List<DocumentRequestUpload>> uploadsByItem = uploadRepository
				.findByItemIdInOrderByCreatedAtAsc(sourceItems.stream().map(DocumentRequestItem::getId).toList())
				.stream()
				.collect(Collectors.groupingBy(upload -> upload.getItem().getId()));

		List<DocumentRequestItem> copied = new ArrayList<>();
		for (DocumentRequestItem source : sourceItems) {
			DocumentRequestItem target = new DocumentRequestItem();
			target.setId(UUID.randomUUID());
			target.setTitle(source.getTitle());
			target.setSortOrder(source.getSortOrder());
			target.setDueDate(source.getDueDate());
			target.setCreatedBy(source.getCreatedBy());
			target.setOrganization(meeting.getOrganization());
			target.setMeeting(meeting);
			copied.add(itemRepository.save(target));
			for (DocumentRequestUpload sourceUpload : uploadsByItem.getOrDefault(source.getId(), List.of())) {
				DocumentRequestUpload upload = new DocumentRequestUpload();
				upload.setId(UUID.randomUUID());
				upload.setItem(copied.get(copied.size() - 1));
				upload.setStoredDocument(sourceUpload.getStoredDocument());
				upload.setUploadedBy(sourceUpload.getUploadedBy());
				uploadRepository.save(upload);
			}
		}
	}

	@Transactional
	public DocumentRequestUploadResponse attachUpload(UUID itemId, AttachDocumentRequestUploadRequest request) {
		DocumentRequestItem item = itemRepository.findById(itemId)
				.orElseThrow(() -> new ResourceNotFoundException("Document request item not found: " + itemId));
		AppUser actor = domainLookupService.requireUser(request.actorId());
		assertOrgMember(actor, item.getOrganization().getId());
		StoredDocument document = storedDocumentRepository.findById(request.storedDocumentId())
				.orElseThrow(() -> new ResourceNotFoundException(
						"Stored document not found: " + request.storedDocumentId()));
		if (document.getUserId() != null && !document.getUserId().equals(actor.getId())) {
			throw new InvalidRequestException("You can only attach documents you uploaded");
		}
		DocumentRequestUpload upload = new DocumentRequestUpload();
		upload.setId(UUID.randomUUID());
		upload.setItem(item);
		upload.setStoredDocument(document);
		upload.setUploadedBy(actor);
		DocumentRequestUpload saved = uploadRepository.saveAndFlush(upload);
		notifyUpload(item, actor);
		return toUploadResponse(saved);
	}

	@Transactional
	public void deleteUpload(UUID uploadId, UUID actorId) {
		DocumentRequestUpload upload = uploadRepository.findById(uploadId)
				.orElseThrow(() -> new ResourceNotFoundException("Document request upload not found: " + uploadId));
		AppUser actor = domainLookupService.requireUser(actorId);
		boolean isUploader = upload.getUploadedBy().getId().equals(actor.getId());
		boolean isMentor = actor.getRole() == UserRole.MENTOR
				&& isMentorForItem(actor, upload.getItem());
		if (!isUploader && !isMentor) {
			throw new InvalidRequestException("Only the uploader or mentor can remove this file");
		}
		uploadRepository.delete(upload);
	}

	// Callers delete the target's existing items first, so a save replaces the checklist.
	private List<DocumentRequestItem> insertItems(
			AppUser actor,
			Organization organization,
			List<String> titles,
			LocalDate dueDate,
			MentorshipRequest request,
			Meeting meeting,
			Mentorship mentorship) {
		itemRepository.flush();
		List<DocumentRequestItem> saved = new ArrayList<>();
		for (int order = 0; order < titles.size(); order++) {
			DocumentRequestItem item = newItem(actor, organization, titles.get(order), order, dueDate);
			item.setMentorshipRequest(request);
			item.setMeeting(meeting);
			item.setMentorship(mentorship);
			saved.add(itemRepository.save(item));
		}
		return saved;
	}

	private static DocumentRequestItem newItem(
			AppUser actor,
			Organization organization,
			String title,
			int order,
			LocalDate dueDate) {
		DocumentRequestItem item = new DocumentRequestItem();
		item.setId(UUID.randomUUID());
		item.setTitle(title);
		item.setSortOrder(order);
		item.setDueDate(dueDate);
		item.setCreatedBy(actor);
		item.setOrganization(organization);
		return item;
	}

	private List<String> normalizeTitles(List<String> titles) {
		if (titles == null || titles.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String title : titles) {
			String trimmed = TextUtils.trim(title);
			if (trimmed == null || trimmed.isBlank()) {
				continue;
			}
			if (trimmed.length() > 500) {
				throw new InvalidRequestException("Document title must be at most 500 characters");
			}
			normalized.add(trimmed);
		}
		return normalized;
	}

	private AppUser requireMentorActor(UUID actorId, UUID mentorId) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.MENTOR || !actor.getId().equals(mentorId)) {
			throw new InvalidRequestException("Only the assigned mentor can manage document requests");
		}
		return actor;
	}

	private AppUser requireMeetingMentor(UUID actorId, Meeting meeting) {
		AppUser actor = domainLookupService.requireUser(actorId);
		if (actor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Only mentors can manage meeting document requests");
		}
		if (meeting.getMentor() != null && !meeting.getMentor().getId().equals(actor.getId())) {
			throw new InvalidRequestException("Only the meeting mentor can manage document requests");
		}
		return actor;
	}

	private boolean isMentorForItem(AppUser actor, DocumentRequestItem item) {
		if (item.getMentorshipRequest() != null) {
			return item.getMentorshipRequest().getMentor().getId().equals(actor.getId());
		}
		if (item.getMeeting() != null && item.getMeeting().getMentor() != null) {
			return item.getMeeting().getMentor().getId().equals(actor.getId());
		}
		if (item.getMentorship() != null) {
			return item.getMentorship().getMentor().getId().equals(actor.getId());
		}
		return false;
	}

	private void assertOrgMember(AppUser actor, UUID organizationId) {
		if (actor.getRole() == UserRole.MENTOR) {
			return;
		}
		if (actor.getOrganization() == null || !actor.getOrganization().getId().equals(organizationId)) {
			throw new InvalidRequestException("You can only upload documents for your organization");
		}
	}

	private void assertExactlyOneTarget(UUID mentorshipRequestId, UUID meetingId, UUID mentorshipId) {
		int count = (mentorshipRequestId != null ? 1 : 0)
				+ (meetingId != null ? 1 : 0)
				+ (mentorshipId != null ? 1 : 0);
		if (count != 1) {
			throw new InvalidRequestException(
					"Provide exactly one of mentorshipRequestId, meetingId, or mentorshipId");
		}
	}

	private void notifyDocumentsRequested(
			Organization organization,
			AppUser requester,
			List<DocumentRequestItem> items,
			String title,
			String href) {
		if (organization == null || items.isEmpty()) {
			return;
		}
		String titles = items.stream().map(DocumentRequestItem::getTitle).collect(Collectors.joining(", "));
		String body = requester.getFullName() + " şu belgeleri istedi: " + titles;
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.forEach(mentee -> notificationService.create(
						mentee,
						NotificationCategory.TASK_ASSIGNED,
						title,
						body,
						href));
	}

	private void notifyUpload(DocumentRequestItem item, AppUser uploader) {
		AppUser mentor = null;
		String href = "/mentorships";
		if (item.getMentorshipRequest() != null) {
			mentor = item.getMentorshipRequest().getMentor();
			href = "/mentorships?tab=requests";
		} else if (item.getMeeting() != null) {
			mentor = item.getMeeting().getMentor();
			href = "/meetings/research?id=" + item.getMeeting().getId();
		} else if (item.getMentorship() != null) {
			mentor = item.getMentorship().getMentor();
			href = "/mentorships?tab=regular";
		}
		if (mentor == null) {
			return;
		}
		notificationService.create(
				mentor,
				NotificationCategory.STARTUP_ACTIVITY,
				"İstenen belge yüklendi",
				uploader.getFullName() + " «" + item.getTitle() + "» belgesini yükledi.",
				href);
	}

	private List<DocumentRequestItemResponse> toResponses(List<DocumentRequestItem> items) {
		if (items.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<DocumentRequestUpload>> uploadsByItem = uploadRepository
				.findByItemIdInOrderByCreatedAtAsc(items.stream().map(DocumentRequestItem::getId).toList())
				.stream()
				.collect(Collectors.groupingBy(upload -> upload.getItem().getId()));
		return items.stream()
				.map(item -> new DocumentRequestItemResponse(
						item.getId(),
						item.getTitle(),
						item.getSortOrder(),
						item.getDueDate(),
						item.getOrganization().getId(),
						item.getMentorshipRequest() == null ? null : item.getMentorshipRequest().getId(),
						item.getMeeting() == null ? null : item.getMeeting().getId(),
						item.getMentorship() == null ? null : item.getMentorship().getId(),
						item.getProgramId(),
						item.getCreatedBy().getId(),
						item.getCreatedBy().getFullName(),
						item.getCreatedAt(),
						uploadsByItem.getOrDefault(item.getId(), List.of()).stream()
								.map(this::toUploadResponse)
								.toList()))
				.toList();
	}

	private DocumentRequestUploadResponse toUploadResponse(DocumentRequestUpload upload) {
		StoredDocument document = upload.getStoredDocument();
		return new DocumentRequestUploadResponse(
				upload.getId(),
				document.getId(),
				document.getOriginalFilename(),
				document.getContentType(),
				document.getSizeBytes(),
				upload.getUploadedBy().getId(),
				upload.getUploadedBy().getFullName(),
				upload.getCreatedAt());
	}
}
