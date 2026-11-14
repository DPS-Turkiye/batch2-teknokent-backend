package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.dps.teknokent.domain.MeetingAssistantShareKind;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.PublishMeetingAssistantShareRequest;
import com.dps.teknokent.dto.responses.MeetingReportActionItem;
import com.dps.teknokent.dto.responses.MeetingReportResponse;
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

import tools.jackson.databind.ObjectMapper;

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

		var response = service().find(meetingId, organizationId, null);

		assertThat(response.meetingId()).isEqualTo(meetingId);
		assertThat(response.transcript()).isNull();
		assertThat(response.report()).isNull();
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void hidesSharesForMeetingsInAnotherOrganization() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());

		assertThatThrownBy(() -> service().find(meetingId, UUID.randomUUID(), null))
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
				mentorId, MeetingAssistantShareKind.TRANSCRIPT, transcript, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
		assertThat(saved.getValue().getTranscript()).contains("Merhaba");
		assertThat(saved.getValue().getTranscriptS3Key()).isNull();
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNotNull();
		assertThat(saved.getValue().getReport()).isNull();
		assertThat(response.transcript().get(0).path("speaker").asString()).isEqualTo("Ayşe");
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
				mentorId, MeetingAssistantShareKind.TRANSCRIPT, transcript, null));

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

		var response = service().find(meetingId, organizationId, null);

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

		var response = service().find(guestId, guestOrgId, null);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asString()).isEqualTo("Merhaba");
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
				MeetingAssistantShareKind.TRANSCRIPT,
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

		var response = service().find(guestId, guestOrgId, null);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asString()).isEqualTo("Merhaba");
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
				MeetingAssistantShareKind.TRANSCRIPT,
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

		var response = service().find(guestId, guestOrgId, null);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript().get(0).path("text").asString()).isEqualTo("Merhaba");
	}

	@Test
	void doesNotShowATeamTheSharePublishedForAnotherSessionInTheMentorsPersonalRoom() {
		UUID otherId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		UUID otherOrgId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		Meeting lastWeek = meeting();
		lastWeek.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		lastWeek.setScheduledAt(Instant.parse("2026-09-08T16:00:00Z"));
		Meeting thisWeek = meeting();
		thisWeek.setId(otherId);
		Organization otherOrg = new Organization();
		otherOrg.setId(otherOrgId);
		otherOrg.setName("OtherCo");
		thisWeek.setOrganization(otherOrg);
		thisWeek.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");

		MeetingAssistantShare published = MeetingAssistantShare.emptyFor(lastWeek);
		published.setTranscript("[{\"speaker\":\"Ayşe\",\"text\":\"Gizli\"}]");
		published.setTranscriptPublishedAt(Instant.parse("2026-09-08T17:00:00Z"));
		published.setReport("{\"title\":\"Başka ekibin raporu\"}");
		published.setReportPublishedAt(Instant.parse("2026-09-08T17:00:00Z"));

		when(domainLookupService.requireMeeting(otherId)).thenReturn(thisWeek);
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("aaa-bbbb-ccc"))
				.thenReturn(List.of(lastWeek, thisWeek));
		when(shareRepository.findByMeetingId(otherId)).thenReturn(Optional.empty());

		var response = service().find(otherId, otherOrgId, null);

		assertThat(response.transcript()).isNull();
		assertThat(response.report()).isNull();
		verify(shareRepository, never()).findByMeetingId(meetingId);
	}

	@Test
	void publishingLeavesAnotherSessionInTheSameRoomAlone() {
		UUID nextWeekId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		Meeting thisSession = meeting();
		thisSession.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		Meeting nextWeek = meeting();
		nextWeek.setId(nextWeekId);
		nextWeek.setMeetLink(thisSession.getMeetLink());
		nextWeek.setScheduledAt(Instant.parse("2026-09-22T16:00:00Z"));
		theMentorPublishesOn(thisSession);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		savingSharesAsTheyAre();
		when(meetingRepository.findByMeetLinkContainingIgnoreCase("aaa-bbbb-ccc"))
				.thenReturn(List.of(thisSession, nextWeek));

		service().publish(meetingId, request(MeetingAssistantShareKind.REPORT, null, REPORT_JSON));

		verify(shareRepository).saveAndFlush(argThatIsFor(meetingId));
		verify(shareRepository, never()).findByMeetingId(nextWeekId);
		verify(meetingNoteService, never()).addReportActionItems(eq(nextWeekId), any());
	}

	@Test
	void aTeamMemberSeesTheirOwnTeamsShareOnTheirBearerToken() {
		UUID menteeId = UUID.randomUUID();
		AppUser mentee = new AppUser();
		mentee.setId(menteeId);
		mentee.setOrganization(meeting().getOrganization());
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(menteeId)).thenReturn(mentee);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());

		assertThat(service().find(meetingId, organizationId, menteeId).meetingId()).isEqualTo(meetingId);
	}

	@Test
	void aSignedInCallerCannotReadAnotherTeamsShareByNamingItsStartup() {
		UUID outsiderId = UUID.randomUUID();
		AppUser outsider = new AppUser();
		outsider.setId(outsiderId);
		Organization elsewhere = new Organization();
		elsewhere.setId(UUID.randomUUID());
		outsider.setOrganization(elsewhere);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(outsiderId)).thenReturn(outsider);

		assertThatThrownBy(() -> service().find(meetingId, organizationId, outsiderId))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void hidesUnpublishedTranscriptArchivedInS3() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, organizationId, null);

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
		assertThat(service().find(meetingId, organizationId, null).transcript().get(0).path("text").asString())
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
		byte[] uploaded = """
				[{"id":"s1","speaker":"Ayşe","text":"Düzenlenmiş merhaba","at":"00:10"}]
				""".getBytes(StandardCharsets.UTF_8);
		when(objectStorage.sizeOf("meetings/" + meetingId + "/transcript.json")).thenReturn((long) uploaded.length);
		when(objectStorage.get("meetings/" + meetingId + "/transcript.json")).thenReturn(uploaded);
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("https://s3.example/transcript");

		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, MeetingAssistantShareKind.TRANSCRIPT_S3, null, null));

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
				MeetingAssistantShareKind.TRANSCRIPT,
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
				mentorId, MeetingAssistantShareKind.TRANSCRIPT, jsonUtils.readTree("[]"), null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript is required.");
	}

	@Test
	void storesAReportProducedByTheServerWithoutAMentor() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service().storeGeneratedReport(meetingId, generatedReport())).isTrue();

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReport()).contains("Otomatik rapor");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
		assertThat(saved.getValue().getPublishedBy()).isNull();
		verify(meetingNoteService).addReportActionItems(meetingId,
				List.of(new MeetingReportActionItem(null, "Yatırımcı listesini hazırla", null)));
	}

	@Test
	void storesAGeneratedReportInS3WhenStorageIsConfigured() {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(objectStorage.isConfigured()).thenReturn(true);

		assertThat(service().storeGeneratedReport(meetingId, generatedReport())).isTrue();

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
		byte[] uploaded = "{\"title\":\"S3 raporu\",\"actionItems\":[\"Demo videosu\",{\"title\":\"Fiyatlandırma\",\"owner\":\"Can\"},{\"owner\":\"Görevsiz\"}]}"
				.getBytes(StandardCharsets.UTF_8);
		when(objectStorage.sizeOf("meetings/" + meetingId + "/report.json")).thenReturn((long) uploaded.length);
		when(objectStorage.get("meetings/" + meetingId + "/report.json")).thenReturn(uploaded);
		when(objectStorage.presignGet("meetings/" + meetingId + "/report.json"))
				.thenReturn("https://s3.example/report");

		var response = service().publish(meetingId, new PublishMeetingAssistantShareRequest(
				mentorId, MeetingAssistantShareKind.REPORT_S3, null, null));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReportS3Key())
				.isEqualTo("meetings/" + meetingId + "/report.json");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
		assertThat(response.report()).isNull();
		assertThat(response.reportUrl()).isEqualTo("https://s3.example/report");
		verify(meetingNoteService).addReportActionItems(meetingId, List.of(
				new MeetingReportActionItem(null, "Demo videosu", null),
				new MeetingReportActionItem("Can", "Fiyatlandırma", null)));
	}

	@Test
	void keepsTheReportTheMentorAlreadyPublished() {
		Meeting meeting = meeting();
		MeetingAssistantShare existing = MeetingAssistantShare.emptyFor(meeting);
		existing.setReport("{\"title\":\"Mentörün raporu\"}");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(existing));

		assertThat(service().storeGeneratedReport(meetingId, generatedReport())).isFalse();

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

		assertThat(service().storeGeneratedReport(meetingId, generatedReport())).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
	}

	private static final String TRANSCRIPT_JSON = "[{\"speaker\":\"Ayşe\",\"text\":\"Merhaba\"}]";
	private static final String REPORT_JSON = "{\"title\":\"Haftalık rapor\",\"actionItems\":[{\"task\":\"Sunumu bitir\",\"owner\":\"Ayşe\",\"due\":\"Cuma\"}]}";
	private static final List<MeetingReportActionItem> REPORT_ACTION_ITEMS = List.of(
			new MeetingReportActionItem("Ayşe", "Sunumu bitir", "Cuma"));

	private static MeetingReportResponse generatedReport() {
		return new MeetingReportResponse("Otomatik rapor", null, null, "Özet", List.of(), List.of(),
				List.of(new MeetingReportActionItem(null, "Yatırımcı listesini hazırla", null)), List.of(), List.of(),
				null);
	}

	private void theMentorPublishesOn(Meeting meeting) {
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(domainLookupService.requireUser(mentorId)).thenReturn(mentor());
	}

	private void savingSharesAsTheyAre() {
		when(shareRepository.saveAndFlush(any(MeetingAssistantShare.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private PublishMeetingAssistantShareRequest request(MeetingAssistantShareKind kind, String transcript,
			String report) {
		return new PublishMeetingAssistantShareRequest(
				mentorId,
				kind,
				transcript == null ? null : jsonUtils.readTree(transcript),
				report == null ? null : jsonUtils.readTree(report));
	}

	@Test
	void publishesAnInlineReportAndTurnsItsActionItemsIntoNotes() {
		theMentorPublishesOn(meeting());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		savingSharesAsTheyAre();

		var response = service().publish(meetingId, request(MeetingAssistantShareKind.REPORT, null, REPORT_JSON));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReport()).contains("Haftalık rapor");
		assertThat(saved.getValue().getReportPublishedAt()).isNotNull();
		assertThat(saved.getValue().getTranscriptPublishedAt()).isNull();
		assertThat(saved.getValue().getPublishedBy().getId()).isEqualTo(mentorId);
		assertThat(response.report().path("title").asString()).isEqualTo("Haftalık rapor");
		verify(meetingNoteService).addReportActionItems(meetingId, REPORT_ACTION_ITEMS);
	}

	@Test
	void publishingAReportCopiesItToTheOtherTeamInTheCallAndAddsItsNotesThereToo() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		Meeting mentorMeeting = meeting();
		mentorMeeting.setMeetLink("https://meet.google.com/aaa-bbbb-ccc");
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		guestMeeting.setMeetLink(mentorMeeting.getMeetLink());
		theMentorPublishesOn(mentorMeeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		savingSharesAsTheyAre();
		when(meetingRepository.findByMeetLinkAndScheduledAt(
				mentorMeeting.getMeetLink(), mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));

		service().publish(meetingId, request(MeetingAssistantShareKind.REPORT, null, REPORT_JSON));

		ArgumentCaptor<MeetingAssistantShare> saved = ArgumentCaptor.forClass(MeetingAssistantShare.class);
		verify(shareRepository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
		assertThat(saved.getAllValues())
				.filteredOn(share -> share.getMeeting().getId().equals(guestId))
				.singleElement()
				.satisfies(copy -> {
					assertThat(copy.getReport()).contains("Haftalık rapor");
					assertThat(copy.getReportPublishedAt()).isNotNull();
					assertThat(copy.getPublishedBy().getId()).isEqualTo(mentorId);
					assertThat(copy.getTranscriptPublishedAt()).isNull();
				});
		verify(meetingNoteService).addReportActionItems(meetingId, REPORT_ACTION_ITEMS);
		verify(meetingNoteService).addReportActionItems(guestId, REPORT_ACTION_ITEMS);
	}

	@Test
	void rejectsAReportThatIsMissingOrNotAnObject() {
		theMentorPublishesOn(meeting());

		assertThatThrownBy(() -> service().publish(meetingId, request(MeetingAssistantShareKind.REPORT, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Report is required.");
		assertThatThrownBy(
				() -> service().publish(meetingId, request(MeetingAssistantShareKind.REPORT, null, "[\"madde\"]")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Report is required.");
		verify(shareRepository, never()).saveAndFlush(any());
		verify(meetingNoteService, never()).addReportActionItems(any(), any());
	}

	@Test
	void rejectsATranscriptThatIsMissingOrNotAList() {
		theMentorPublishesOn(meeting());

		assertThatThrownBy(
				() -> service().publish(meetingId, request(MeetingAssistantShareKind.TRANSCRIPT, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript is required.");
		assertThatThrownBy(() -> service().publish(meetingId,
				request(MeetingAssistantShareKind.TRANSCRIPT, "{\"text\":\"Merhaba\"}", null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript is required.");
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsAMissingKind() {
		theMentorPublishesOn(meeting());

		assertThatThrownBy(() -> service().publish(meetingId, request(null, TRANSCRIPT_JSON, REPORT_JSON)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("kind must be");
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void onlyASignedInApprovedMentorCanPublish() {
		AppUser mentee = mentor();
		mentee.setId(UUID.randomUUID());
		mentee.setRole(UserRole.MENTEE);
		AppUser waitingForApproval = mentor();
		waitingForApproval.setId(UUID.randomUUID());
		waitingForApproval.setStatus(UserAccountStatus.PENDING);
		when(domainLookupService.requireUser(mentee.getId())).thenReturn(mentee);
		when(domainLookupService.requireUser(waitingForApproval.getId())).thenReturn(waitingForApproval);
		var transcript = jsonUtils.readTree(TRANSCRIPT_JSON);

		assertThatThrownBy(() -> service().publish(meetingId,
				new PublishMeetingAssistantShareRequest(null, MeetingAssistantShareKind.TRANSCRIPT, transcript, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("A signed-in user is required.");
		assertThatThrownBy(() -> service().publish(meetingId,
				new PublishMeetingAssistantShareRequest(mentee.getId(), MeetingAssistantShareKind.TRANSCRIPT,
						transcript, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Only a mentor can do this.");
		assertThatThrownBy(() -> service().publish(meetingId,
				new PublishMeetingAssistantShareRequest(waitingForApproval.getId(),
						MeetingAssistantShareKind.TRANSCRIPT, transcript, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Only an approved mentor can publish to a notebook.");
		verify(shareRepository, never()).saveAndFlush(any());
		verify(objectStorage, never()).put(anyString(), any(), anyString());
	}

	@Test
	void nobodyPublishesToAMeetingThatHasNoMentor() {
		Meeting meeting = meeting();
		meeting.setMentor(null);
		theMentorPublishesOn(meeting);

		assertThatThrownBy(() -> service().publish(meetingId,
				request(MeetingAssistantShareKind.TRANSCRIPT, TRANSCRIPT_JSON, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Only the meeting's mentor");
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void refusesAnUploadLinkWhenStorageIsNotConfigured() {
		theMentorPublishesOn(meeting());

		assertThatThrownBy(() -> service().createTranscriptUpload(meetingId, mentorId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Object storage is not configured.");
		verify(objectStorage, never()).presignPut(anyString(), anyString());
	}

	@Test
	void refusesAnUploadLinkForAnotherMentorsMeeting() {
		AppUser other = mentor();
		other.setId(UUID.randomUUID());
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting());
		when(domainLookupService.requireUser(other.getId())).thenReturn(other);

		assertThatThrownBy(() -> service().createReportUpload(meetingId, other.getId()))
				.isInstanceOf(InvalidRequestException.class);
		verify(objectStorage, never()).presignPut(anyString(), anyString());
	}

	@Test
	void publishingAnUploadNeedsStorageAndAnUploadThatIsThere() {
		theMentorPublishesOn(meeting());

		assertThatThrownBy(
				() -> service().publish(meetingId, request(MeetingAssistantShareKind.TRANSCRIPT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Object storage is not configured.");

		when(objectStorage.isConfigured()).thenReturn(true);
		assertThatThrownBy(
				() -> service().publish(meetingId, request(MeetingAssistantShareKind.TRANSCRIPT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript upload was not found.");

		when(objectStorage.sizeOf("meetings/" + meetingId + "/report.json")).thenReturn(0L);
		assertThatThrownBy(() -> service().publish(meetingId, request(MeetingAssistantShareKind.REPORT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Report upload was not found.");
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void anUploadedTranscriptMustBeANonEmptyListAndAnUploadedReportAnObject() {
		theMentorPublishesOn(meeting());
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.sizeOf(anyString())).thenReturn(100L);
		when(objectStorage.get("meetings/" + meetingId + "/transcript.json"))
				.thenReturn("[]".getBytes(StandardCharsets.UTF_8));
		when(objectStorage.get("meetings/" + meetingId + "/report.json"))
				.thenReturn(TRANSCRIPT_JSON.getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(
				() -> service().publish(meetingId, request(MeetingAssistantShareKind.TRANSCRIPT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Transcript is required.");
		assertThatThrownBy(() -> service().publish(meetingId, request(MeetingAssistantShareKind.REPORT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Report is required.");
		verify(shareRepository, never()).saveAndFlush(any());
		verify(meetingNoteService, never()).addReportActionItems(any(), any());
	}

	@Test
	void refusesAnUploadOverTheSizeLimitWithoutReadingIt() {
		theMentorPublishesOn(meeting());
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.sizeOf("meetings/" + meetingId + "/transcript.json"))
				.thenReturn(MeetingAssistantShareService.MAX_UPLOAD_BYTES + 1);

		assertThatThrownBy(() -> service().publish(meetingId,
				request(MeetingAssistantShareKind.TRANSCRIPT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("10 MB");
		verify(objectStorage, never()).get(anyString());
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void refusesAnUploadThatGrewPastTheLimitAfterItsSizeWasChecked() {
		theMentorPublishesOn(meeting());
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.sizeOf("meetings/" + meetingId + "/report.json")).thenReturn(10L);
		when(objectStorage.get("meetings/" + meetingId + "/report.json"))
				.thenReturn(new byte[(int) MeetingAssistantShareService.MAX_UPLOAD_BYTES + 1]);

		assertThatThrownBy(() -> service().publish(meetingId,
				request(MeetingAssistantShareKind.REPORT_S3, null, null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("10 MB");
		verify(shareRepository, never()).saveAndFlush(any());
	}

	@Test
	void storesNothingForAGeneratedTranscriptWithoutSegments() {
		assertThat(service().storeGeneratedTranscript(meetingId, List.of())).isFalse();
		assertThat(service().storeGeneratedTranscript(meetingId, null)).isFalse();

		verifyNoInteractions(shareRepository, objectStorage, domainLookupService);
	}

	@Test
	void doesNotRecordTheGeneratedReportWhenTheMentorPublishedOneWhileItUploaded() {
		Meeting meeting = meeting();
		MeetingAssistantShare publishedMeanwhile = MeetingAssistantShare.emptyFor(meeting);
		publishedMeanwhile.setReport("{\"title\":\"Mentörün raporu\"}");
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(publishedMeanwhile));

		assertThat(service().storeGeneratedReport(meetingId, generatedReport())).isFalse();

		verify(shareRepository, never()).saveAndFlush(any());
		verify(meetingNoteService, never()).addReportActionItems(any(), any());
	}

	@Test
	void showsTheTeamAPublishedReportButNotATranscriptThatIsStillUnpublished() {
		Meeting meeting = meeting();
		Instant published = Instant.parse("2026-09-01T10:00:00Z");
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscript(TRANSCRIPT_JSON);
		share.setReport(REPORT_JSON);
		share.setReportPublishedAt(published);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, organizationId, null);

		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isNull();
		assertThat(response.report().path("title").asString()).isEqualTo("Haftalık rapor");
		assertThat(response.reportPublishedAt()).isEqualTo(published);
	}

	@Test
	void showsTheMentorAReportTheyHaveNotPublishedYet() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setReport(REPORT_JSON);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, null, mentorId);

		assertThat(response.report().path("title").asString()).isEqualTo("Haftalık rapor");
		assertThat(response.reportPublishedAt()).isNull();
		assertThat(response.transcript()).isNull();
	}

	@Test
	void doesNotShowATeamTheUnpublishedTranscriptOfAnotherTeamsCopy() {
		UUID guestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		UUID guestOrgId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		Meeting mentorMeeting = meeting();
		Meeting guestMeeting = meeting();
		guestMeeting.setId(guestId);
		Organization guestOrg = new Organization();
		guestOrg.setId(guestOrgId);
		guestOrg.setName("GuestCo");
		guestMeeting.setOrganization(guestOrg);
		MeetingAssistantShare unpublished = MeetingAssistantShare.emptyFor(mentorMeeting);
		unpublished.setTranscript(TRANSCRIPT_JSON);
		when(domainLookupService.requireMeeting(guestId)).thenReturn(guestMeeting);
		when(meetingRepository.findByMentorIdAndScheduledAt(mentorId, mentorMeeting.getScheduledAt()))
				.thenReturn(List.of(mentorMeeting, guestMeeting));
		when(shareRepository.findByMeetingId(guestId)).thenReturn(Optional.empty());
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(unpublished));

		var response = service().find(guestId, guestOrgId, null);

		assertThat(response.meetingId()).isEqualTo(guestId);
		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptUrl()).isNull();
	}

	@Test
	void aViewerIsNotTakenForTheMentorOfAMeetingThatHasNone() {
		Meeting meeting = meeting();
		meeting.setMentor(null);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);

		assertThatThrownBy(() -> service().find(meetingId, null, mentorId))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(shareRepository);
	}

	@Test
	void fallsBackToTheCopyKeptInTheDatabaseWhenNoDownloadLinkCanBeMade() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		share.setTranscript(TRANSCRIPT_JSON);
		share.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		share.setReportS3Key("meetings/" + meetingId + "/report.json");
		share.setReport(REPORT_JSON);
		share.setReportPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));
		when(objectStorage.isConfigured()).thenReturn(true);
		when(objectStorage.presignGet("meetings/" + meetingId + "/transcript.json"))
				.thenThrow(new IllegalStateException("S3 is unreachable"));
		when(objectStorage.presignGet("meetings/" + meetingId + "/report.json")).thenReturn(" ");

		var response = service().find(meetingId, organizationId, null);

		assertThat(response.transcriptUrl()).isNull();
		assertThat(response.transcript().get(0).path("text").asString()).isEqualTo("Merhaba");
		assertThat(response.reportUrl()).isNull();
		assertThat(response.report().path("title").asString()).isEqualTo("Haftalık rapor");
	}

	@Test
	void readsTheDatabaseCopyWhenAnS3KeyIsKeptButStorageIsNoLongerConfigured() {
		Meeting meeting = meeting();
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscriptS3Key("meetings/" + meetingId + "/transcript.json");
		share.setTranscript(TRANSCRIPT_JSON);
		share.setTranscriptPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, organizationId, null);

		assertThat(response.transcript().get(0).path("speaker").asString()).isEqualTo("Ayşe");
		assertThat(response.transcriptUrl()).isNull();
		verify(objectStorage, never()).presignGet(anyString());
	}

	@Test
	void aStoredTranscriptThatIsNotJsonIsShownAsMissingInsteadOfFailing() {
		Meeting meeting = meeting();
		Instant published = Instant.parse("2026-09-01T10:00:00Z");
		MeetingAssistantShare share = MeetingAssistantShare.emptyFor(meeting);
		share.setTranscript("[{\"speaker\":");
		share.setTranscriptPublishedAt(published);
		when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
		when(shareRepository.findByMeetingId(meetingId)).thenReturn(Optional.of(share));

		var response = service().find(meetingId, organizationId, null);

		assertThat(response.transcript()).isNull();
		assertThat(response.transcriptPublishedAt()).isEqualTo(published);
	}

	private static MeetingAssistantShare argThatIsFor(UUID id) {
		return org.mockito.ArgumentMatchers.argThat(share -> share.getMeeting().getId().equals(id));
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
