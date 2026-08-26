package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.dto.requests.CreateActionItemRequest;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ActionItemRepository;

@ExtendWith(MockitoExtension.class)
class ActionItemServiceTest {

    @Mock
    private ActionItemRepository actionItemRepository;

    @Mock
    private DomainLookupService domainLookupService;

    @InjectMocks
    private ActionItemService actionItemService;

    @Test
    void findByOrganization_returnsMappedResponsesInReverseChronologicalOrder() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        Organization organization = organization(organizationId, "Teknokent");
        AppUser owner = user(UUID.fromString("00000000-0000-0000-0000-000000000201"), "Ayşe Yılmaz");
        ActionItem actionItem = new ActionItem();
        actionItem.setId(UUID.fromString("00000000-0000-0000-0000-000000000301"));
        actionItem.setOrganization(organization);
        actionItem.setOwner(owner);
        actionItem.setTitle("Onboarding checklist");
        actionItem.setDescription("Document all setup steps");
        actionItem.setStatus(ActionItemStatus.OPEN);
        actionItem.setDueDate(LocalDate.of(2026, 9, 15));
        actionItem.setCompletionNotes(null);
        actionItem.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        actionItem.setUpdatedAt(Instant.parse("2026-08-20T10:05:00Z"));

        when(actionItemRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId))
                .thenReturn(List.of(actionItem));

        var result = actionItemService.findByOrganization(organizationId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(actionItem.getId());
        assertThat(result.getFirst().organizationId()).isEqualTo(organization.getId());
        assertThat(result.getFirst().title()).isEqualTo("Onboarding checklist");
        assertThat(result.getFirst().status()).isEqualTo(ActionItemStatus.OPEN);
    }

    @Test
    void create_createsActionItemWithOpenStatus() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000110");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        Organization organization = organization(organizationId, "Teknokent");
        Meeting meeting = new Meeting();
        meeting.setId(meetingId);
        meeting.setOrganization(organization);
        AppUser owner = user(ownerId, "Ayşe Yılmaz");

        CreateActionItemRequest request = new CreateActionItemRequest(
                organizationId,
                meetingId,
                ownerId,
                "Prepare onboarding checklist",
                "Review onboarding docs and assign owners",
                LocalDate.of(2026, 9, 15));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);
        when(domainLookupService.requireUser(ownerId)).thenReturn(owner);
        when(actionItemRepository.saveAndFlush(any(ActionItem.class))).thenAnswer(invocation -> {
            ActionItem saved = invocation.getArgument(0);
            saved.setId(UUID.fromString("00000000-0000-0000-0000-000000000301"));
            saved.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
            saved.setUpdatedAt(Instant.parse("2026-08-20T10:05:00Z"));
            return saved;
        });

        var result = actionItemService.create(request);

        assertThat(result.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000301"));
        assertThat(result.organizationName()).isEqualTo("Teknokent");
        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.title()).isEqualTo("Prepare onboarding checklist");
        assertThat(result.status()).isEqualTo(ActionItemStatus.OPEN);
    }

    @Test
    void create_throwsWhenMeetingBelongsToAnotherOrganization() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID otherOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000999");
        UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000110");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        Organization organization = organization(organizationId, "Teknokent");
        Organization otherOrganization = organization(otherOrganizationId, "Another Org");
        Meeting meeting = new Meeting();
        meeting.setId(meetingId);
        meeting.setOrganization(otherOrganization);
        AppUser owner = user(ownerId, "Ayşe Yılmaz");

        CreateActionItemRequest request = new CreateActionItemRequest(
                organizationId,
                meetingId,
                ownerId,
                "Prepare onboarding checklist",
                "Review onboarding docs and assign owners",
                LocalDate.of(2026, 9, 15));

        when(domainLookupService.requireOrganization(organizationId)).thenReturn(organization);
        when(domainLookupService.requireMeeting(meetingId)).thenReturn(meeting);

        assertThatThrownBy(() -> actionItemService.create(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("same organization");
    }

    @Test
    void update_updatesExistingActionItem() {
        UUID actionItemId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000201");

        Organization organization = organization(organizationId, "Teknokent");
        AppUser oldOwner = user(UUID.fromString("00000000-0000-0000-0000-000000000202"), "Esra Demir");
        AppUser newOwner = user(ownerId, "Ayşe Yılmaz");

        ActionItem actionItem = new ActionItem();
        actionItem.setId(actionItemId);
        actionItem.setOrganization(organization);
        actionItem.setOwner(oldOwner);
        actionItem.setTitle("Prepare onboarding checklist");
        actionItem.setDescription("Old description");
        actionItem.setStatus(ActionItemStatus.OPEN);
        actionItem.setDueDate(LocalDate.of(2026, 9, 15));
        actionItem.setCompletionNotes(null);
        actionItem.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        actionItem.setUpdatedAt(Instant.parse("2026-08-20T10:05:00Z"));

        UpdateActionItemRequest request = new UpdateActionItemRequest(
                ownerId,
                "Finalize onboarding",
                "All handoff docs completed",
                ActionItemStatus.COMPLETED,
                LocalDate.of(2026, 9, 20),
                "Completed after stakeholder review");

        when(actionItemRepository.findByIdAndOrganizationId(actionItemId, organizationId))
                .thenReturn(Optional.of(actionItem));
        when(domainLookupService.requireUser(ownerId)).thenReturn(newOwner);
        when(actionItemRepository.saveAndFlush(actionItem)).thenReturn(actionItem);

        var result = actionItemService.update(actionItemId, organizationId, request);

        assertThat(result.title()).isEqualTo("Finalize onboarding");
        assertThat(result.status()).isEqualTo(ActionItemStatus.COMPLETED);
        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.completionNotes()).isEqualTo("Completed after stakeholder review");
    }

    @Test
    void findByOrganization_neverReturnsAnotherOrganizationsActionItems() {
        UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        when(actionItemRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId))
                .thenReturn(List.of());

        assertThat(actionItemService.findByOrganization(organizationId)).isEmpty();
    }

    @Test
    void update_throwsWhenActionItemBelongsToAnotherOrganization() {
        UUID actionItemId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID otherOrganizationId = UUID.fromString("00000000-0000-0000-0000-000000000999");

        UpdateActionItemRequest request = new UpdateActionItemRequest(
                null, "Hijacked title", null, ActionItemStatus.COMPLETED, null, null);

        when(actionItemRepository.findByIdAndOrganizationId(actionItemId, otherOrganizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> actionItemService.update(actionItemId, otherOrganizationId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Action item not found");
    }

    private static Organization organization(UUID id, String name) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setName(name);
        return organization;
    }

    private static AppUser user(UUID id, String fullName) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setFullName(fullName);
        return user;
    }
}
