package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentRequestService {

	private final DocumentRequestItemRepository itemRepository;
	private final DocumentRequestUploadRepository uploadRepository;
	private final StoredDocumentRepository storedDocumentRepository;
	private final MentorshipRepository mentorshipRepository;
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

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnRequest(
			UUID mentorshipRequestId,
			UUID actorId,
			List<String> titles) {
		MentorshipRequest request = domainLookupService.requireMentorshipRequest(mentorshipRequestId);
		AppUser actor = requireMentorActor(actorId, request.getMentor().getId());
		List<DocumentRequestItem> saved = replaceItems(
				actor,
				request.getOrganization(),
				normalizeTitles(titles),
				request,
				null,
				null);
		notifyDocumentsRequested(request.getOrganization(), actor, saved, "/mentorships?tab=active");
		return toResponses(saved);
	}

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnMeeting(
			UUID meetingId,
			UUID actorId,
			List<String> titles) {
		Meeting meeting = domainLookupService.requireMeeting(meetingId);
		AppUser actor = requireMeetingMentor(actorId, meeting);
		List<DocumentRequestItem> saved = replaceItems(
				actor,
				meeting.getOrganization(),
				normalizeTitles(titles),
				null,
				meeting,
				null);
		notifyDocumentsRequested(
				meeting.getOrganization(),
				actor,
				saved,
				"/meetings/research?id=" + meeting.getId());
		return toResponses(saved);
	}

	@Transactional
	public List<DocumentRequestItemResponse> replaceOnMentorship(
			UUID mentorshipId,
			UUID actorId,
			List<String> titles) {
		Mentorship mentorship = mentorshipRepository.findById(mentorshipId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship not found: " + mentorshipId));
		AppUser actor = requireMentorActor(actorId, mentorship.getMentor().getId());
		List<DocumentRequestItem> saved = replaceItems(
				actor,
				mentorship.getOrganization(),
				normalizeTitles(titles),
				null,
				null,
				mentorship);
		notifyDocumentsRequested(
				mentorship.getOrganization(),
				actor,
				saved,
				"/mentorships?tab=regular");
		return toResponses(saved);
	}

	@Transactional
	public void saveTitlesOnRequest(MentorshipRequest request, AppUser mentor, List<String> titles) {
		List<String> normalized = normalizeTitles(titles);
		if (normalized.isEmpty()) {
			return;
		}
		List<DocumentRequestItem> saved = replaceItems(
				mentor,
				request.getOrganization(),
				normalized,
				request,
				null,
				null);
		notifyDocumentsRequested(request.getOrganization(), mentor, saved, "/mentorships?tab=active");
	}

	@Transactional
	public void saveTitlesOnMeeting(Meeting meeting, AppUser mentor, List<String> titles) {
		List<String> normalized = normalizeTitles(titles);
		if (normalized.isEmpty()) {
			return;
		}
		List<DocumentRequestItem> saved = replaceItems(
				mentor,
				meeting.getOrganization(),
				normalized,
				null,
				meeting,
				null);
		notifyDocumentsRequested(
				meeting.getOrganization(),
				mentor,
				saved,
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

	private List<DocumentRequestItem> replaceItems(
			AppUser actor,
			Organization organization,
			List<String> titles,
			MentorshipRequest request,
			Meeting meeting,
			Mentorship mentorship) {
		if (request != null) {
			itemRepository.deleteByMentorshipRequestId(request.getId());
		} else if (meeting != null) {
			itemRepository.deleteByMeetingId(meeting.getId());
		} else if (mentorship != null) {
			itemRepository.deleteByMentorshipId(mentorship.getId());
		}
		itemRepository.flush();
		List<DocumentRequestItem> saved = new ArrayList<>();
		int order = 0;
		for (String title : titles) {
			DocumentRequestItem item = new DocumentRequestItem();
			item.setId(UUID.randomUUID());
			item.setTitle(title);
			item.setSortOrder(order++);
			item.setCreatedBy(actor);
			item.setOrganization(organization);
			item.setMentorshipRequest(request);
			item.setMeeting(meeting);
			item.setMentorship(mentorship);
			saved.add(itemRepository.save(item));
		}
		return saved;
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
			AppUser mentor,
			List<DocumentRequestItem> items,
			String href) {
		if (organization == null || items.isEmpty()) {
			return;
		}
		String titles = items.stream().map(DocumentRequestItem::getTitle).collect(Collectors.joining(", "));
		String body = mentor.getFullName() + " şu belgeleri istedi: " + titles;
		appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.forEach(mentee -> notificationService.create(
						mentee,
						"Mentor belgeler istedi",
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
						item.getOrganization().getId(),
						item.getMentorshipRequest() == null ? null : item.getMentorshipRequest().getId(),
						item.getMeeting() == null ? null : item.getMeeting().getId(),
						item.getMentorship() == null ? null : item.getMentorship().getId(),
						item.getCreatedBy().getId(),
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
