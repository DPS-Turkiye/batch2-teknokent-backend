package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ShareDocumentRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.dto.responses.OrganizationDocumentResponse;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.entity.StoredDocumentShare;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.DocumentRequestUploadRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.repository.StoredDocumentShareRepository;

@ExtendWith(MockitoExtension.class)
class StartupDocumentServiceTest {

    private static final UUID ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ORG_ID = UUID.fromString("10000000-0000-0000-0000-000000000009");
    private static final UUID FOUNDER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TEAMMATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID MENTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID MEETING_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Mock
    private StoredDocumentRepository storedDocumentRepository;

    @Mock
    private StoredDocumentShareRepository storedDocumentShareRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private MentorshipRequestRepository mentorshipRequestRepository;

    @Mock
    private MentorshipRepository mentorshipRepository;

    @Mock
    private ProgramMentorRepository programMentorRepository;

    @Mock
    private DocumentRequestUploadRepository documentRequestUploadRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @Mock
    private ObjectStorage objectStorage;

    @InjectMocks
    private StartupDocumentService startupDocumentService;

    @Test
    void listForOrganization_describesTheUploaderTheMeetingAndTheShares() {
        Organization organization = startup();
        AppUser teammate = member(TEAMMATE_ID, "Sude Arya", organization);
        StoredDocument deck = document(TEAMMATE_ID);
        deck.setMeetingId(MEETING_ID);
        Meeting meeting = meeting(organization, null);
        StoredDocumentShare share = new StoredDocumentShare();
        share.setDocumentId(deck.getId());
        share.setMentor(mentor());
        share.setSharedAt(Instant.parse("2026-09-05T10:00:00Z"));
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(teammate);
        when(storedDocumentRepository.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID)).thenReturn(List.of(deck));
        when(appUserRepository.findAllById(Set.of(TEAMMATE_ID))).thenReturn(List.of(teammate));
        when(meetingRepository.findAllById(Set.of(MEETING_ID))).thenReturn(List.of(meeting));
        when(storedDocumentShareRepository.findByDocumentIdIn(Set.of(deck.getId()))).thenReturn(List.of(share));

        var documents = startupDocumentService.listForOrganization(ORG_ID, TEAMMATE_ID);

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().uploadedByName()).isEqualTo("Sude Arya");
        assertThat(documents.getFirst().meetingAgenda()).isEqualTo("MVP Scope & User Flow");
        assertThat(documents.getFirst().shares()).singleElement()
                .satisfies(shared -> assertThat(shared.mentorName()).isEqualTo("Nihan Öztürk"));
    }

    @Test
    void listForOrganization_refusesSomeoneOutsideTheStartup() {
        Organization organization = startup();
        Organization otherStartup = new Organization();
        otherStartup.setId(OTHER_ORG_ID);
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Dış Kişi", otherStartup));

        assertThatThrownBy(() -> startupDocumentService.listForOrganization(ORG_ID, TEAMMATE_ID))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listForOrganization_letsACoordinator() {
        AppUser coordinator = member(TEAMMATE_ID, "Koordinatör", null);
        coordinator.setRole(UserRole.COORDINATOR);
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(startup());
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(coordinator);

        assertThat(startupDocumentService.listForOrganization(ORG_ID, TEAMMATE_ID)).isEmpty();
    }

    @Test
    void downloadUrl_givesTheTeamAPresignedLink() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));
        when(objectStorage.presignInline(anyString(), anyString(), anyString()))
                .thenReturn("https://bucket.s3/pitch-deck.pdf?sig");

        var link = startupDocumentService.downloadUrl(deck.getId(), TEAMMATE_ID);

        assertThat(link.url()).isEqualTo("https://bucket.s3/pitch-deck.pdf?sig");
        assertThat(link.filename()).isEqualTo("pitch-deck.pdf");
    }

    @Test
    void downloadUrl_letsAMentorItWasSharedWith() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(true);
        when(objectStorage.presignInline(anyString(), anyString(), anyString()))
                .thenReturn("https://bucket.s3/pitch-deck.pdf?sig");

        assertThat(startupDocumentService.downloadUrl(deck.getId(), MENTOR_ID).url()).startsWith("https://");
    }

    @Test
    void downloadUrl_letsTheMentorWhoAskedForTheFile() {
        StoredDocument notes = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(notes.getId())).thenReturn(Optional.of(notes));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(documentRequestUploadRepository.existsVisibleToMentor(notes.getId(), MENTOR_ID)).thenReturn(true);
        when(objectStorage.presignInline(anyString(), anyString(), anyString()))
                .thenReturn("https://bucket.s3/requested.pdf?sig");

        assertThat(startupDocumentService.downloadUrl(notes.getId(), MENTOR_ID).url())
                .isEqualTo("https://bucket.s3/requested.pdf?sig");
    }

    @Test
    void downloadUrl_letsTheMentorOfItsMeeting() {
        StoredDocument notes = document(FOUNDER_ID);
        notes.setMeetingId(MEETING_ID);
        AppUser mentor = mentor();
        when(storedDocumentRepository.findById(notes.getId())).thenReturn(Optional.of(notes));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(notes.getId(), MENTOR_ID)).thenReturn(false);
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(startup(), mentor)));
        when(objectStorage.presignInline(anyString(), anyString(), anyString())).thenReturn("https://bucket.s3/notes.pdf?sig");

        assertThat(startupDocumentService.downloadUrl(notes.getId(), MENTOR_ID).url()).startsWith("https://");
    }

    @Test
    void downloadUrl_refusesAMentorItWasNeverShown() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> startupDocumentService.downloadUrl(deck.getId(), MENTOR_ID))
                .isInstanceOf(InvalidRequestException.class);

        verify(objectStorage, never()).presignInline(anyString(), anyString(), anyString());
    }

    @Test
    void share_recordsTheShareForAMentorOfTheStartup() {
        StoredDocument deck = document(FOUNDER_ID);
        AppUser teammate = member(TEAMMATE_ID, "Sude Arya", startup());
        AppUser mentor = mentor();
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(teammate);
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor);
        when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                MENTOR_ID, ORG_ID, MentorshipRequestStatus.ACCEPTED)).thenReturn(Optional.of(new MentorshipRequest()));
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(false);
        ArgumentCaptor<StoredDocumentShare> saved = ArgumentCaptor.forClass(StoredDocumentShare.class);

        startupDocumentService.share(deck.getId(), new ShareDocumentRequest(TEAMMATE_ID, MENTOR_ID));

        verify(storedDocumentShareRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getMentor()).isSameAs(mentor);
        assertThat(saved.getValue().getSharedBy()).isSameAs(teammate);
    }

    @Test
    void share_doesNotDuplicateAnExistingShare() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                MENTOR_ID, ORG_ID, MentorshipRequestStatus.ACCEPTED)).thenReturn(Optional.of(new MentorshipRequest()));
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(true);

        startupDocumentService.share(deck.getId(), new ShareDocumentRequest(TEAMMATE_ID, MENTOR_ID));

        verify(storedDocumentShareRepository, never()).saveAndFlush(any(StoredDocumentShare.class));
    }

    @Test
    void share_refusesAMentorWithoutAnAcceptedMentorship() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(mentorshipRequestRepository.findFirstByMentorIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
                MENTOR_ID, ORG_ID, MentorshipRequestStatus.ACCEPTED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> startupDocumentService.share(deck.getId(),
                new ShareDocumentRequest(TEAMMATE_ID, MENTOR_ID)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("mentor of this startup");
    }

    @Test
    void share_refusesSomeoneOutsideTheStartup() {
        StoredDocument deck = document(FOUNDER_ID);
        Organization otherStartup = new Organization();
        otherStartup.setId(OTHER_ORG_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Dış Kişi", otherStartup));

        assertThatThrownBy(() -> startupDocumentService.share(deck.getId(),
                new ShareDocumentRequest(TEAMMATE_ID, MENTOR_ID)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void delete_letsTheUploaderDeleteItAndRemovesTheStoredObject() {
        StoredDocument deck = document(TEAMMATE_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));

        startupDocumentService.delete(deck.getId(), TEAMMATE_ID);

        verify(storedDocumentShareRepository).deleteByDocumentId(deck.getId());
        verify(storedDocumentRepository).delete(deck);
        verify(objectStorage).delete(deck.getS3Key());
    }

    @Test
    void delete_stillRemovesTheRecordWhenStorageFails() {
        StoredDocument deck = document(TEAMMATE_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));
        doThrow(new InvalidRequestException("The file could not be deleted.")).when(objectStorage).delete(anyString());

        startupDocumentService.delete(deck.getId(), TEAMMATE_ID);

        verify(storedDocumentRepository).delete(deck);
    }

    @Test
    void delete_letsTheFounderDeleteATeammatesUpload() {
        Organization organization = startup();
        StoredDocument deck = document(TEAMMATE_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(FOUNDER_ID)).thenReturn(organization.getFounder());
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);

        startupDocumentService.delete(deck.getId(), FOUNDER_ID);

        verify(storedDocumentRepository).delete(deck);
    }

    @Test
    void delete_refusesATeammateWhoDidNotUploadIt() {
        Organization organization = startup();
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", organization));
        when(domainLookupService.requireOrganization(ORG_ID)).thenReturn(organization);

        assertThatThrownBy(() -> startupDocumentService.delete(deck.getId(), TEAMMATE_ID))
                .isInstanceOf(InvalidRequestException.class);

        verify(storedDocumentRepository, never()).delete(any(StoredDocument.class));
    }

    @Test
    void review_letsAMentorItWasSharedWithMarkItReviewed() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(true);
        when(appUserRepository.findAllById(any())).thenReturn(List.of(mentor()));

        OrganizationDocumentResponse reviewed = startupDocumentService.review(deck.getId(), MENTOR_ID);

        verify(storedDocumentRepository).saveAndFlush(deck);
        assertThat(deck.getReviewedById()).isEqualTo(MENTOR_ID);
        assertThat(reviewed.reviewedAt()).isNotNull();
        assertThat(reviewed.reviewedByName()).isEqualTo("Nihan Öztürk");
    }

    @Test
    void review_refusesTheStartupsOwnTeam() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(TEAMMATE_ID)).thenReturn(member(TEAMMATE_ID, "Sude Arya", startup()));

        assertThatThrownBy(() -> startupDocumentService.review(deck.getId(), TEAMMATE_ID))
                .isInstanceOf(InvalidRequestException.class);
        verify(storedDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    void review_refusesAMentorWhoCannotOpenTheDocument() {
        StoredDocument deck = document(FOUNDER_ID);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());
        when(storedDocumentShareRepository.existsByDocumentIdAndMentorId(deck.getId(), MENTOR_ID)).thenReturn(false);

        assertThatThrownBy(() -> startupDocumentService.review(deck.getId(), MENTOR_ID))
                .isInstanceOf(InvalidRequestException.class);
        verify(storedDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    void review_refusesAMentorReviewingTheirOwnUpload() {
        StoredDocument notes = document(MENTOR_ID);
        when(storedDocumentRepository.findById(notes.getId())).thenReturn(Optional.of(notes));
        when(domainLookupService.requireUser(MENTOR_ID)).thenReturn(mentor());

        assertThatThrownBy(() -> startupDocumentService.review(notes.getId(), MENTOR_ID))
                .isInstanceOf(InvalidRequestException.class);
        verify(storedDocumentRepository, never()).saveAndFlush(any());
    }

    @Test
    void review_letsACoordinatorMarkItReviewed() {
        StoredDocument deck = document(FOUNDER_ID);
        AppUser coordinator = new AppUser();
        coordinator.setId(UUID.fromString("20000000-0000-0000-0000-000000000009"));
        coordinator.setFullName("Ece Koordinatör");
        coordinator.setRole(UserRole.COORDINATOR);
        when(storedDocumentRepository.findById(deck.getId())).thenReturn(Optional.of(deck));
        when(domainLookupService.requireUser(coordinator.getId())).thenReturn(coordinator);

        startupDocumentService.review(deck.getId(), coordinator.getId());

        verify(storedDocumentRepository).saveAndFlush(deck);
        assertThat(deck.getReviewedById()).isEqualTo(coordinator.getId());
    }

    private static Organization startup() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        organization.setName("Döngü");
        organization.setFounder(member(FOUNDER_ID, "Gülse Buran", organization));
        return organization;
    }

    private static AppUser member(UUID id, String fullName, Organization organization) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setFullName(fullName);
        user.setRole(UserRole.MENTEE);
        user.setOrganization(organization);
        return user;
    }

    private static AppUser mentor() {
        AppUser mentor = new AppUser();
        mentor.setId(MENTOR_ID);
        mentor.setFullName("Nihan Öztürk");
        mentor.setRole(UserRole.MENTOR);
        return mentor;
    }

    private static Meeting meeting(Organization organization, AppUser mentor) {
        Meeting meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setOrganization(organization);
        meeting.setMentor(mentor);
        meeting.setAgenda("MVP Scope & User Flow");
        meeting.setScheduledAt(Instant.parse("2026-08-18T09:00:00Z"));
        return meeting;
    }

    private static StoredDocument document(UUID uploaderId) {
        StoredDocument document = new StoredDocument();
        document.setId(UUID.randomUUID());
        document.setUserId(uploaderId);
        document.setOrganizationId(ORG_ID);
        document.setKind("pitch-deck");
        document.setOriginalFilename("pitch-deck.pdf");
        document.setContentType("application/pdf");
        document.setSizeBytes(4_200_000);
        document.setS3Key("documents/" + uploaderId + "/pitch-deck/pitch-deck.pdf");
        document.setCreatedAt(Instant.parse("2026-09-12T08:00:00Z"));
        return document;
    }
}
