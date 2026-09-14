package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.DocumentRequestItem;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestItemRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;

@ExtendWith(MockitoExtension.class)
class DocumentRequestServiceTest {

	@Mock
	private DocumentRequestItemRepository itemRepository;
	@Mock
	private DocumentRequestUploadRepository uploadRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private MentorshipRepository mentorshipRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private NotificationService notificationService;

	@InjectMocks
	private DocumentRequestService documentRequestService;

	@Test
	void copyFromRequestToMeetingCopiesTitlesWhenMeetingHasNone() {
		UUID requestId = UUID.randomUUID();
		UUID meetingId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		AppUser mentor = new AppUser();
		mentor.setId(UUID.randomUUID());
		mentor.setRole(UserRole.MENTOR);
		mentor.setFullName("Mentor");

		MentorshipRequest request = new MentorshipRequest();
		request.setId(requestId);
		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);

		DocumentRequestItem source = new DocumentRequestItem();
		source.setId(UUID.randomUUID());
		source.setTitle("Pitch deck");
		source.setSortOrder(0);
		source.setCreatedBy(mentor);
		source.setOrganization(organization);
		source.setMentorshipRequest(request);

		when(itemRepository.findByMeetingIdOrderBySortOrderAscCreatedAtAsc(meetingId))
				.thenReturn(List.of());
		when(itemRepository.findByMentorshipRequestIdOrderBySortOrderAscCreatedAtAsc(requestId))
				.thenReturn(List.of(source));
		when(uploadRepository.findByItemIdInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());
		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

		documentRequestService.copyFromRequestToMeeting(request, meeting);

		ArgumentCaptor<DocumentRequestItem> captor = ArgumentCaptor.forClass(DocumentRequestItem.class);
		verify(itemRepository).save(captor.capture());
		assertThat(captor.getValue().getTitle()).isEqualTo("Pitch deck");
		assertThat(captor.getValue().getMeeting()).isEqualTo(meeting);
		assertThat(captor.getValue().getMentorshipRequest()).isNull();
	}

	@Test
	void saveTitlesOnRequestNotifiesOrgMentees() {
		UUID requestId = UUID.randomUUID();
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName("Startup");
		AppUser mentor = new AppUser();
		mentor.setId(UUID.randomUUID());
		mentor.setRole(UserRole.MENTOR);
		mentor.setFullName("Mentor Ali");
		AppUser mentee = new AppUser();
		mentee.setId(UUID.randomUUID());
		mentee.setRole(UserRole.MENTEE);
		mentee.setFullName("Mentee");

		MentorshipRequest request = new MentorshipRequest();
		request.setId(requestId);
		request.setOrganization(organization);
		request.setMentor(mentor);

		when(itemRepository.save(any(DocumentRequestItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(organization.getId()))
				.thenReturn(List.of(mentee));

		documentRequestService.saveTitlesOnRequest(request, mentor, List.of("CV", "  "));

		verify(notificationService).create(
				eq(mentee),
				eq("Mentor belgeler istedi"),
				eq("Mentor Ali şu belgeleri istedi: CV"),
				eq("/mentorships?tab=active"));
	}
}
