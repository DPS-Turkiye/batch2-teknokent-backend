package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.responses.TranscriptSegmentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MeetingAssistantShare;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingAssistantShareRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingAssistantShareServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private MeetingAssistantShareRepository shareRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private ObjectStorage objectStorage;
	@Mock
	private MeetingNoteService meetingNoteService;
	@Mock
	private PlatformTransactionManager transactionManager;

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000401");

	private MeetingAssistantShareService service() {
		return new MeetingAssistantShareService(
				domainLookupService, shareRepository, meetingRepository, jsonUtils, objectStorage,
				meetingNoteService, transactionManager);
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
		verify(objectStorage, never()).put(anyString(), any(), anyString());
		assertThat(saved.getValue().getTranscript()).contains("Merhaba");
		assertThat(saved.getValue().getTranscriptS3Key()).isNull();
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNotNull();
		assertThat(saved.getValue().getReport()).isNull();
		assertThat(response.transcript().get(0).path("speaker").asText()).isEqualTo("Ayşe");
	}

	@Test
	void storesTranscriptInS3WhenStorageIsConfigured() {
		Meeting meeting = meeting();
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("https://s3.example/transcript");

		var transcript = jsonUtils.readTree("""
				[{"id":"s1","speaker":"Ayşe","text":"Merhaba","at":"00:10"}]
				""");
		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, "TRANSCRIPT", transcript, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
		verify(shareRepository).saveAndFlush(saved.capture());
		verify(objectStorage).put(
				eq("meetings/" + meetingId + "/transcript.json"),
				body.capture(),
				eq("application/json"));
		assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).contains("Merhaba");
		// Checked in one short transaction, stored in S3 with none open, recorded in a second one.
		InOrder order = inOrder(transactionManager, objectStorage, shareRepository);
		order.verify(transactionManager).commit(any());
		order.verify(objectStorage).put(anyString(), any(), anyString());
		order.verify(transactionManager).getTransaction(any());
		order.verify(shareRepository).saveAndFlush(any(MeetingAssistantShare.class));
		assertThat(saved.getValue().getTranscript()).isNull();
		assertThat(saved.getValue().getTranscriptS3Key())
				.isEqualTo("meetings/" + meetingId + "/transcript.json");
		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isEqualTo("https://s3.example/transcript");
	}

	@Test
	void readsTranscriptFromS3Key() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		share.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("https://s3.example/transcript");

		var response = service().find(meetingId, organizationId);

		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isEqualTo("https://s3.example/transcript");
		assertThat(response.transcriptPublishedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
	}

	@Test
	void showsAPublishedRoommateShareToTheMenteeMeeting() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		UUID guestOrgId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		Meeting mentorMeeting = meeting();
		mentorMeeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		Organization guestOrg = new Organization();
		guestOrg.setId(guestOrgId);
		guestOrg.setName("GuestCo");
		guestMeeting.setOrganization(guestOrg);
		guestMeeting.setMeetLink(mentorMeeting.getMeetLink());

		MeetingAssistantShare published = MeetingAssistantShare.emptyFor(mentorMeeting);
		published.setTranscript("[{\"speaker\":\"Ayşe\",\"text\":\"Merhaba\"}]");
		published.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));

		when(domainLookupService.requireMeeting(guestId)).thenReturn(guestMeeting);
		when(meetingRepository.findByMeetLinkAndScheduledAt(
				mentorMeeting.getMeetLink(), mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(published));

		var response = service().find(guestId, guestOrgId);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asText()).isEqualTo("Merhaba");
	}

	@Test
	void copiesAPublishedTranscriptOntoRoommateMeetings() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		Meeting mentorMeeting = meeting();
		mentorMeeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		guestMeeting.setMeetLink(mentorMeeting.getMeetLink());
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(mentorMeeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(meetingRepository.findByMeetLinkAndScheduledAt(
				mentorMeeting.getMeetLink(), mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));

		service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId,
				"TRANSCRIPT",
				jsonUtils.readTree("""
						[{"id":"s1","speaker":"Ayşe","text":"Merhaba","at":"00:10"}]
						"""),
				null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository, org.mockito.Mockito.atLeast(2)).saveAndFlush(saved.capture());
		assertThat(saved.getAllValues())
				.anyMatch(share -> share.getMeeting().getId().equals(guestId)
						&& share.getTranscriptPublishedAt() != null);
	}

	@Test
	void showsAPublishedShareToTheMenteeCopyWithoutAMeetLink() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		UUID guestOrgId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		Meeting mentorMeeting = meeting();
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		Organization guestOrg = new Organization();
		guestOrg.setId(guestOrgId);
		guestOrg.setName("GuestCo");
		guestMeeting.setOrganization(guestOrg);

		MeetingAssistantShare published = MeetingAssistantShare.emptyFor(mentorMeeting);
		published.setTranscript("[{\"speaker\":\"Ayşe\",\"text\":\"Merhaba\"}]");
		published.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));

		when(domainLookupService.requireMeeting(guestId)).thenReturn(guestMeeting);
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(published));

		var response = service().find(guestId, guestOrgId);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asText()).isEqualTo("Merhaba");
	}

	@Test
	void copiesAPublishedTranscriptOntoTheMenteeCopyWithoutAMeetLink() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		Meeting mentorMeeting = meeting();
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(mentorMeeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));

		service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId,
				"TRANSCRIPT",
				jsonUtils.readTree("""
						[{"id":"s1","speaker":"Ayşe","text":"Merhaba","at":"00:10"}]
						"""),
				null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository, org.mockito.Mockito.atLeast(2)).saveAndFlush(saved.capture());
		assertThat(saved.getAllValues())
				.anyMatch(share -> share.getMeeting().getId().equals(guestId)
						&& share.getTranscriptPublishedAt() != null);
	}

	@Test
	void showsAPublishedShareWhenTheRoommateMeetLinkOnlySharesTheNativeId() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		UUID guestOrgId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		Meeting mentorMeeting = meeting();
		mentorMeeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		guestMeeting.setScheduledAt(Instant.parse("2026-09-15T16:30:00Z"));
		Organization guestOrg = new Organization();
		guestOrg.setId(guestOrgId);
		guestOrg.setName("GuestCo");
		guestMeeting.setOrganization(guestOrg);
		guestMeeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc?authuser=0");

		MeetingAssistantShare published = MeetingAssistantShare.emptyFor(mentorMeeting);
		published.setTranscript("[{\"speaker\":\"Ayşe\",\"text\":\"Merhaba\"}]");
		published.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));

		when(domainLookupService.requireMeeting(guestId)).thenReturn(guestMeeting);
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("aaa-bbbb-ccc"))
				.thenReturn(List.of(mentorMeeting, guestMeeting));
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(published));

		var response = service().find(guestId, guestOrgId);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asText()).isEqualTo("Merhaba");
	}

	@Test
	void hidesUnpublishedTranscriptArchivedInS3() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, organizationId);

		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptPublishedAt()).isNull();
		verify(objectStorage, never()).get(anyString());
		verify(objectStorage, never()).presignGet(anyString());
	}

	@Test
	void showsUnpublishedTranscriptToTheMeetingMentor() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("https://s3.example/transcript");

		var response = service().find(meetingId, null, mentorId);

		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isEqualTo("https://s3.example/transcript");
		assertThat(response.transcriptPublishedAt()).isNull();
	}

	@Test
	void archivesGeneratedTranscriptWithoutPublishingToTheNotebook() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);

		assertThat(service().storeGeneratedTranscript(meetingId, List.of(
				new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "00:10")))).isTrue();

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		verify(objectStorage).put(
				eq("meetings/" + meetingId + "/transcript.json"),
				any(),
				eq("application/json"));
		assertThat(saved.getValue().getTranscriptS3Key())
				.isEqualTo("meetings/" + meetingId + "/transcript.json");
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNull();
		assertThat(saved.getValue().getTranscript()).isNull();
	}

	@Test
	void sharesTheGeneratedTranscriptWhenTheMentorAskedForItToBeSent() {
		Meeting meeting = meeting();
		meeting.setTranscribeRequested(true);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service().storeGeneratedTranscript(meetingId, List.of(
				new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "00:10")))).isTrue();

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNotNull();
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(saved.getValue()));
		assertThat(service().find(meetingId, organizationId).transcript().get(0).path("text").asText())
				.isEqualTo("Merhaba");
	}

	@Test
	void keepsATranscriptTheMentorAlreadyStored() {
		Meeting meeting = meeting();
		MeetingAssistantShare existing = MeetingAssistantShare.emptyFor(meeting);
		existing.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		existing.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(existing));

		assertThat(service().storeGeneratedTranscript(meetingId, List.of(
				new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "00:10")))).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
	}

	@Test
	void doesNotRecordTheGeneratedTranscriptWhenTheMentorStoredOneWhileItUploaded() {
		Meeting meeting = meeting();
		MeetingAssistantShare storedMeanwhile = MeetingAssistantShare.emptyFor(meeting);
		storedMeanwhile.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(storedMeanwhile));
		when(objectStorage.isConfigured()).thenReturn(true);

		assertThat(service().storeGeneratedTranscript(meetingId, List.of(
				new TranscriptSegmentResponse("s1", "Ayşe", "Merhaba", "00:10")))).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void issuesAPresignedPutUrlForTheMeetingsMentor() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignPut(eq("meetings/" + meetingId + "/transcript.json"), eq("application/json")))
				.thenReturn("https://s3.example/put");

		var response = service().createTranscriptUpload(meetingId, mentorId);

		assertThat(response.url()).isEqualTo("https://s3.example/put");
		assertThat(response.method()).isEqualTo("PUT");
		assertThat(response.key()).isEqualTo("meetings/" + meetingId + "/transcript.json");
		assertThat(response.contentType()).isEqualTo("application/json");
	}

	@Test
	void publishesTheTranscriptAlreadyUploadedToS3() {
		Meeting meeting = meeting();
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.get("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("""
						[{"id":"s1","speaker":"Ayşe","text":"Düzenlenmiş merhaba","at":"00:10"}]
						""".getBytes(StandardCharsets.UTF_8));
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("https://s3.example/transcript");

		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, "TRANSCRIPT_S3", null, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
		assertThat(saved.getValue().getTranscriptS3Key())
				.isEqualTo("meetings/" + meetingId + "/transcript.json");
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNotNull();
		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isEqualTo("https://s3.example/transcript");
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
		verify(objectStorage, never()).put(anyString(), any(), anyString());
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

	@Test
	void storesAReportProducedByTheServerWithoutAMentor() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service().storeGeneratedReport(meetingId, Map.of("title", "Otomatik rapor"))).isTrue();

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReport()).contains("Otomatik rapor");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
		assertThat(saved.getValue().getPublishedBy()).isNull();
		verify(meetingNoteService).addReportActionItems(eq(meetingId), any());
	}

	@Test
	void storesAGeneratedReportInS3WhenStorageIsConfigured() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);

		assertThat(service().storeGeneratedReport(meetingId, Map.of("title", "Otomatik rapor"))).isTrue();

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		verify(objectStorage).put(
				eq("meetings/" + meetingId + "/report.json"),
				any(),
				eq("application/json"));
		assertThat(saved.getValue().getReport()).isNull();
		assertThat(saved.getValue().getReportS3Key())
				.isEqualTo("meetings/" + meetingId + "/report.json");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
	}

	@Test
	void issuesAPresignedPutUrlForTheReport() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignPut(eq("meetings/" + meetingId + "/report.json"), eq("application/json")))
				.thenReturn("https://s3.example/report-put");

		var response = service().createReportUpload(meetingId, mentorId);

		assertThat(response.url()).isEqualTo("https://s3.example/report-put");
		assertThat(response.key()).isEqualTo("meetings/" + meetingId + "/report.json");
	}

	@Test
	void publishesTheReportAlreadyUploadedToS3() {
		Meeting meeting = meeting();
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.get("meetings/" + meetingId + "/report.json"))
				.thenReturn("{\"title\":\"S3 raporu\"}".getBytes(StandardCharsets.UTF_8));
		when(objectStorage.presignGet("meetings/" + meetingId + "/report.json"))
				.thenReturn("https://s3.example/report");

		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, "REPORT_S3", null, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReportS3Key())
				.isEqualTo("meetings/" + meetingId + "/report.json");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
		assertThat(response.report()).isNull();
		assertThat(response.reportUrl()).isEqualTo("https://s3.example/report");
		verify(meetingNoteService).addReportActionItems(
				eq(meetingId), argThat(report -> "S3 raporu".equals(report.path("title").asText())));
	}

	@Test
	void keepsTheReportTheMentorAlreadyPublished() {
		Meeting meeting = meeting();
		MeetingAssistantShare existing = MeetingAssistantShare.emptyFor(meeting);
		existing.setReport("{\"title\":\"Mentörün raporu\"}");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(existing));

		assertThat(service().storeGeneratedReport(meetingId, Map.of("title", "Otomatik rapor"))).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
		verify(meetingNoteService, never()).addReportActionItems(any(), any());
	}

	@Test
	void keepsAReportAlreadyArchivedInS3() {
		Meeting meeting = meeting();
		MeetingAssistantShare existing = MeetingAssistantShare.emptyFor(meeting);
		existing.setReportS3Key("meetings/" + meetingId + "/report.json");
		existing.setReportPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(existing));

		assertThat(service().storeGeneratedReport(meetingId, Map.of("title", "Otomatik rapor"))).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
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
