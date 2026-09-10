package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.dto.requests.AcceptProposedSlotRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.DecideMentorshipRequest;
import com.dps.teknokent.dto.requests.PostMentorMessageRequest;
import com.dps.teknokent.dto.requests.ReplyToMentorshipRequest;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorshipRequestMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRecommendationRunRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.MeetingRepository;

@ExtendWith(MockitoExtension.class)
class MentorshipRequestServiceTest {

        @Mock
        private MentorshipRequestRepository mentorshipRequestRepository;

        @Mock
        private MentorshipRequestRecommendationRunRepository recommendationRunRepository;

        @Mock
        private MentorshipRequestMessageRepository mentorshipRequestMessageRepository;

        @Mock
        private MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;

        @Mock
        private MeetingRepository meetingRepository;

        @Mock
        private DomainLookupService domainLookupService;

        @Mock
        private MentorProfileService mentorProfileService;

        @Mock
    private NotificationService notificationService;

    @InjectMocks
        private MentorshipRequestService mentorshipRequestService;

        @Test
        void findAll_returnsMappedRequestsInDescendingCreationOrder() {
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");

                MentorshipRequest first = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000401"),
                                requester, organization, mentor, "Roadmap help", MentorshipRequestStatus.PENDING);
                MentorshipRequest second = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000402"),
                                requester, organization, mentor, "Hiring strategy", MentorshipRequestStatus.ACCEPTED);

                when(mentorshipRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId))
                                .thenReturn(List.of(first, second));
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(any()))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(any())).thenReturn(Optional.empty());

                var result = mentorshipRequestService.findAll(requesterId, null, null);

                assertThat(result).hasSize(2);
                assertThat(result.getFirst().requesterId()).isEqualTo(requesterId);
                assertThat(result.get(1).status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
        }

        @Test
        void findAll_listsEveryRequestForTheStartup() {
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"),
                                "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser teammate = user(UUID.fromString("00000000-0000-0000-0000-000000000102"),
                                "teammate@tekno.com", "Mert Kaya", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(organizationId, "Teknokent");

                MentorshipRequest first = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000401"),
                                requester, organization, mentor, "Roadmap help", MentorshipRequestStatus.PENDING);
                MentorshipRequest second = mentorshipRequest(UUID.fromString("00000000-0000-0000-0000-000000000402"),
                                teammate, organization, mentor, "Hiring strategy", MentorshipRequestStatus.ACCEPTED);

                when(mentorshipRequestRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId))
                                .thenReturn(List.of(first, second));
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(any()))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(any())).thenReturn(Optional.empty());

                var result = mentorshipRequestService.findAll(null, null, organizationId);

                assertThat(result).hasSize(2);
                assertThat(result.getFirst().organizationId()).isEqualTo(organizationId);
                assertThat(result.get(1).requesterId()).isEqualTo(teammate.getId());
        }

        @Test
        void create_requiresRequesterToBeMenteeAndMentorToBeMentor() {
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");

                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTOR);

                when(domainLookupService.requireUser(requesterId)).thenReturn(requester);

                CreateMentorshipRequestSubmission request = new CreateMentorshipRequestSubmission(
                                requesterId,
                                organizationId,
                                mentorId,
                                "Arda Demir",
                                "Product strategy",
                                "We need help validating our roadmap.",
                                MentorshipRequestUrgency.THIS_WEEK,
                                MentorshipRequestFormat.SINGLE_MEETING,
                                MeetingMode.ONLINE,
                                "Need guidance",
                                null);

                assertThatThrownBy(() -> mentorshipRequestService.create(request))
                                .isInstanceOf(InvalidRequestException.class)
                                .hasMessageContaining("MENTEE role");
        }

        @Test
        void create_savesRequestAndMapsResponseWhenInputsAreValid() {
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000301");
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");

                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(organizationId, "Teknokent");

                when(domainLookupService.requireUser(requesterId)).thenReturn(requester);
                when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
                when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
                when(mentorshipRequestRepository.saveAndFlush(any(MentorshipRequest.class))).thenAnswer(invocation -> {
                        MentorshipRequest saved = invocation.getArgument(0);
                        saved.setId(requestId);
                        saved.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
                        saved.setUpdatedAt(Instant.parse("2026-08-21T08:00:00Z"));
                        return saved;
                });
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                CreateMentorshipRequestSubmission request = new CreateMentorshipRequestSubmission(
                                requesterId,
                                organizationId,
                                mentorId,
                                "Arda Demir",
                                "Product strategy",
                                "We need help validating our roadmap.",
                                MentorshipRequestUrgency.THIS_WEEK,
                                MentorshipRequestFormat.SINGLE_MEETING,
                                MeetingMode.ONLINE,
                                "Need guidance",
                                null);

                var result = mentorshipRequestService.create(request);

                assertThat(result.id()).isEqualTo(requestId);
                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
                assertThat(result.mentorId()).isEqualTo(mentorId);
                assertThat(result.summary()).isEqualTo("We need help validating our roadmap.");
        }

        @Test
        void decide_acceptsRequestWhenAssignedMentorConfirms() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
                                "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help", MentorshipRequestStatus.PENDING);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
                when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.decide(requestId,
                                new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.ACCEPTED, "Let's meet"));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                assertThat(result.mentorMessage()).isEqualTo("Let's meet");
                assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
        }

        @Test
        void decide_counterProposesAlternativeSlots() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
                                "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help", MentorshipRequestStatus.PENDING);
                String message = "Alternatif öneriyorum.\nproposedSlots:2026-09-12T15:00,2026-09-13T10:30";

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(domainLookupService.requireUser(mentorId)).thenReturn(mentor);
                when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.decide(requestId,
                                new DecideMentorshipRequest(mentorId, MentorshipRequestStatus.COUNTER_PROPOSED,
                                                message));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
                assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.COUNTER_PROPOSED);
                assertThat(request.getMentorMessage()).contains("proposedSlots:2026-09-12T15:00");
                verify(mentorshipRequestMessageRepository).saveAndFlush(
                                argThat((MentorshipRequestMessage saved) -> saved
                                                .getMessageType() == MentorshipRequestMessageType.MENTOR_MESSAGE));
        }

        @Test
        void acceptProposedSlot_acceptsWhenMenteePicksAProposedTime() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help", MentorshipRequestStatus.COUNTER_PROPOSED);
                request.setMentorMessage("proposedSlots:2026-09-12T15:00,2026-09-13T10:30");

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.acceptProposedSlot(requestId,
                                new AcceptProposedSlotRequest(requesterId, "2026-09-12T15:00"));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                assertThat(request.getMentorMessage()).isEqualTo("Görüşme saati: 2026-09-12T15:00");
        }

        @Test
        void reply_savesMenteeReplyAndMarksRepliedWhenMoreInfoRequested() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.MORE_INFO_REQUESTED);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.reply(requestId,
                                new ReplyToMentorshipRequest(requesterId, "Bütçemiz 50.000 TL"));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.PENDING);
                assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
                verify(mentorshipRequestMessageRepository).saveAndFlush(
                                argThat((MentorshipRequestMessage message) -> message
                                                .getMessageType() == MentorshipRequestMessageType.MENTEE_REPLY
                                                && message.getBody().equals("Bütçemiz 50.000 TL")));
        }

        @Test
        void reply_savesMenteeMessageWithoutChangingStatusWhenAccepted() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.ACCEPTED);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(mentorshipRequestRepository.saveAndFlush(request)).thenReturn(request);
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.reply(requestId,
                                new ReplyToMentorshipRequest(requesterId, "Salı uygun mu?"));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                assertThat(request.getStatus()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                verify(mentorshipRequestMessageRepository).saveAndFlush(
                                argThat((MentorshipRequestMessage message) -> message
                                                .getMessageType() == MentorshipRequestMessageType.MENTEE_MESSAGE
                                                && message.getBody().equals("Salı uygun mu?")));
        }

        @Test
        void reply_rejectsWhenRequestIsStillPending() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID requesterId = UUID.fromString("00000000-0000-0000-0000-000000000101");
                AppUser requester = user(requesterId, "requester@tekno.com", "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "mentor@tekno.com",
                                "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.PENDING);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

                assertThatThrownBy(() -> mentorshipRequestService.reply(requestId,
                                new ReplyToMentorshipRequest(requesterId, "Merhaba")))
                                .isInstanceOf(InvalidRequestException.class)
                                .hasMessageContaining("more information was requested or the request was accepted");
        }

        @Test
        void postMentorMessage_savesMentorMessageWhenAccepted() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
                                "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.ACCEPTED);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
                when(mentorshipRequestMessageRepository.findByRequestIdOrderByCreatedAtAsc(requestId))
                                .thenReturn(List.of());
                when(recommendationRunRepository.findByRequestId(requestId)).thenReturn(Optional.empty());

                var result = mentorshipRequestService.postMentorMessage(requestId,
                                new PostMentorMessageRequest(mentorId, "Yarın 14:00 uygun mu?"));

                assertThat(result.status()).isEqualTo(MentorshipRequestStatus.ACCEPTED);
                verify(mentorshipRequestMessageRepository).saveAndFlush(
                                argThat((MentorshipRequestMessage message) -> message
                                                .getMessageType() == MentorshipRequestMessageType.MENTOR_MESSAGE
                                                && message.getBody().equals("Yarın 14:00 uygun mu?")));
        }

        @Test
        void postMentorMessage_rejectsWhenMentorIsNotAssignedToRequest() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                UUID otherMentorId = UUID.fromString("00000000-0000-0000-0000-000000000999");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
                                "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.ACCEPTED);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

                assertThatThrownBy(() -> mentorshipRequestService.postMentorMessage(requestId,
                                new PostMentorMessageRequest(otherMentorId, "Merhaba")))
                                .isInstanceOf(InvalidRequestException.class)
                                .hasMessageContaining("assigned mentor");
        }

        @Test
        void postMentorMessage_rejectsWhenRequestIsNotAccepted() {
                UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000401");
                UUID mentorId = UUID.fromString("00000000-0000-0000-0000-000000000201");
                AppUser requester = user(UUID.fromString("00000000-0000-0000-0000-000000000101"), "requester@tekno.com",
                                "Ayşe Demir", UserRole.MENTEE);
                AppUser mentor = user(mentorId, "mentor@tekno.com", "Dr. Kemal Arslan", UserRole.MENTOR);
                Organization organization = organization(UUID.fromString("00000000-0000-0000-0000-000000000301"),
                                "Teknokent");
                MentorshipRequest request = mentorshipRequest(requestId, requester, organization, mentor,
                                "Roadmap help",
                                MentorshipRequestStatus.PENDING);

                when(mentorshipRequestRepository.findById(requestId)).thenReturn(Optional.of(request));

                assertThatThrownBy(() -> mentorshipRequestService.postMentorMessage(requestId,
                                new PostMentorMessageRequest(mentorId, "Merhaba")))
                                .isInstanceOf(InvalidRequestException.class)
                                .hasMessageContaining("accepted");
        }

        private static MentorshipRequest mentorshipRequest(UUID id, AppUser requester, Organization organization,
                        AppUser mentor,
                        String summary, MentorshipRequestStatus status) {
                MentorshipRequest request = new MentorshipRequest();
                request.setId(id);
                request.setRequester(requester);
                request.setOrganization(organization);
                request.setMentor(mentor);
                request.setRequestedForName("Arda Demir");
                request.setSupportArea("Product strategy");
                request.setSummary(summary);
                request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
                request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
                request.setMeetingMode(MeetingMode.ONLINE);
                request.setStatus(status);
                request.setRequesterMessage("Need guidance");
                request.setCreatedAt(Instant.parse("2026-08-21T08:00:00Z"));
                request.setUpdatedAt(Instant.parse("2026-08-21T08:01:00Z"));
                return request;
        }

        private static Organization organization(UUID id, String name) {
                Organization organization = new Organization();
                organization.setId(id);
                organization.setName(name);
                return organization;
        }

        private static AppUser user(UUID id, String email, String fullName, UserRole role) {
                AppUser user = new AppUser();
                user.setId(id);
                user.setEmail(email);
                user.setFullName(fullName);
                user.setRole(role);
                return user;
        }
}
