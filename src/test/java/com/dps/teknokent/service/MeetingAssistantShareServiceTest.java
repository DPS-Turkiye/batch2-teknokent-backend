package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingAssistantShare;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingAssistantShareRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingAssistantShareServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MeetingAssistantShareRepository shareRepository;

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000401");

	private MeetingAssistantShareService service() {
		return new MeetingAssistantShareService(domainLookupService, shareRepository, jsonUtils);
	}

	@Test
	void returnsEmptyShareWhenNothingHasBeenPublished() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());

		var response = service().find(meetingId, organizationId);

		assertThat(response.meetingId()).isEqualTo(meetingId);
		assertThat(response.transcript()).isNull();
		assertThat(response.report()).isNull();
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void hidesSharesForMeetingsInAnotherOrganization() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());

		assertThatThrownBy(() -> service().find(meetingId, UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void returnsShareWhenViewerIsTheMeetingMentor() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());

		var response = service().find(meetingId, null, mentorId);

		assertThat(response.meetingId()).isEqualTo(meetingId);
		assertThat(response.transcript()).isNull();
	}

	@Test
	void hidesSharesFromAnUnrelatedViewer() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());

		assertThatThrownBy(() -> service().find(meetingId, null, UUID.randomUUID()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void publishesTranscriptForTheMeetingsMentor() {
		Meeting meeting = meeting();
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		var transcript = jsonUtils.readTree("""
				[{"id":"s1","speaker":"Ayşe","text":"Merhaba","at":"00:10"}]
				""");
		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, "TRANSCRIPT", transcript, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTranscript()).contains("Merhaba");
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNotNull();
		assertThat(saved.getValue().getReport()).isNull();
		assertThat(response.transcript().get(0).path("speaker").asText()).isEqualTo("Ayşe");
	}

	@Test
	void rejectsPublishFromADifferentMentor() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		AppUser other = mentor();
		other.setId(UUID.randomUUID());
		when(domainLookupService.requireUser(other.getId())).thenReturn(other);

		assertThatThrownBy(() -> service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				other.getId(),
				"TRANSCRIPT",
				jsonUtils.readTree("[{\"id\":\"s1\",\"speaker\":\"A\",\"text\":\"Hi\",\"at\":\"00:01\"}]"),
				null)))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void rejectsEmptyTranscript() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());

		assertThatThrownBy(() -> service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, "TRANSCRIPT", jsonUtils.readTree("[]"), null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript is required.");
	}

	private Meeting meeting() {
		Organization organization = new Organization();
		organization.setId(organizationId);
		organization.setName("NovaMed");

		Meeting meeting = new Meeting();
		meeting.setId(meetingId);
		meeting.setOrganization(organization);
		meeting.setMentor(mentor());
		meeting.setScheduledAt(Instant.parse("2026-09-15T16:00:00Z"));
		meeting.setDurationMinutes(45);
		meeting.setStatus(MeetingStatus.SCHEDULED);
		return meeting;
	}

	private AppUser mentor() {
		AppUser user = new AppUser();
		user.setId(mentorId);
		user.setFullName("Leyla Aksoy");
		user.setRole(UserRole.MENTOR);
		user.setStatus(UserAccountStatus.APPROVED);
		return user;
	}
}
