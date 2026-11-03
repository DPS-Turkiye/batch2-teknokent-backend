package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.AssignMentorshipTaskRequest;
import com.dps.teknokent.dto.requests.CreateMentorshipRequestSubmission;
import com.dps.teknokent.dto.requests.UpdateActionItemRequest;
import com.dps.teknokent.dto.responses.TaskAssigneeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.NotificationRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class MentorshipTaskFlowTest {

    @Autowired private MentorshipTaskService tasks;
    @Autowired private ActionItemService actionItems;
    @Autowired private ActionItemRepository items;
    @Autowired private NotificationRepository notifications;
    @Autowired private EntityManager entityManager;

    private Organization team;
    private AppUser founder;
    private AppUser member;
    private AppUser mentor;
    private AppUser outsider;
    private AppUser otherMentor;
    private AppUser pending;
    private MentorshipRequest request;

    @BeforeEach
    void acceptedMentorshipWithApprovedAndPendingTeamMembers() {
        team = new Organization();
        team.setName("Assignment Team");
        entityManager.persist(team);
        founder = user("assignment-founder@flow.test", UserRole.MENTEE, team);
        member = user("assignment-member@flow.test", UserRole.MENTEE, team);
        team.setFounder(founder);
        pending = user("assignment-pending@flow.test", UserRole.MENTEE, team);
        pending.setStatus(UserAccountStatus.PENDING);
        mentor = user("assignment-mentor@flow.test", UserRole.MENTOR, null);
        otherMentor = user("assignment-other-mentor@flow.test", UserRole.MENTOR, null);
        Organization otherTeam = new Organization();
        otherTeam.setName("Unrelated Team");
        entityManager.persist(otherTeam);
        outsider = user("assignment-outsider@flow.test", UserRole.MENTEE, otherTeam);
        request = MentorshipRequest.fromRequest(new CreateMentorshipRequestSubmission(
                founder.getId(), team.getId(), mentor.getId(), team.getName(), "Product", "Prepare a launch",
                MentorshipRequestUrgency.THIS_MONTH, MentorshipRequestFormat.SINGLE_MEETING,
                MeetingMode.ONLINE, null, null, null), founder, team, mentor);
        request.setStatus(MentorshipRequestStatus.ACCEPTED);
        entityManager.persist(request);
        entityManager.flush();
    }

    @Test
    void onlyApprovedMembersOfTheSelectedStartupAreOfferedAsAssignees() {
        assertThat(tasks.assignees(mentor.getId(), request.getId()))
                .extracting(TaskAssigneeResponse::id).containsExactlyInAnyOrder(founder.getId(), member.getId());
    }

    @Test
    void anAssignmentPersistsInBothViewsAndCompletionNotifiesTheMentor() {
        LocalDate due = LocalDate.now().plusDays(7);
        var assigned = tasks.assign(mentor.getId(), request.getId(),
                new AssignMentorshipTaskRequest(member.getId(), "  Interview five users  ", "  Record findings  ", due));
        entityManager.flush();
        entityManager.clear();
        var stored = items.findById(assigned.id()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Interview five users");
        assertThat(stored.getDescription()).isEqualTo("Record findings");
        assertThat(stored.getOwner().getId()).isEqualTo(member.getId());
        assertThat(stored.getOrganization().getId()).isEqualTo(team.getId());
        assertThat(stored.getMentorshipRequest().getId()).isEqualTo(request.getId());
        assertThat(stored.getDueDate()).isEqualTo(due);
        assertThat(stored.getStatus()).isEqualTo(ActionItemStatus.OPEN);
        assertThat(actionItems.findByRequests(List.of(request.getId()), null, mentor.getId()))
                .extracting(item -> item.id()).containsExactly(assigned.id());
        assertThat(actionItems.findByRequests(List.of(request.getId()), team.getId(), null))
                .extracting(item -> item.id()).containsExactly(assigned.id());
        assertThat(actionItems.findByRequests(List.of(request.getId()), outsider.getOrganization().getId(), null)).isEmpty();
        actionItems.update(assigned.id(), team.getId(), null,
                new UpdateActionItemRequest(null, null, null, ActionItemStatus.COMPLETED, null, "Done"));
        entityManager.flush();
        entityManager.clear();
        assertThat(items.findById(assigned.id()).orElseThrow().getStatus()).isEqualTo(ActionItemStatus.COMPLETED);
        assertThat(notifications.findByRecipientIdOrderByCreatedAtDesc(mentor.getId()))
                .extracting(Notification::getHref).contains("/mentor-dashboard");
    }

    @Test
    void unrelatedMentorsAndTeamMembersCannotReadAssigneesOrAssignTasks() {
        for (AppUser actor : List.of(otherMentor, founder, outsider)) {
            assertThatThrownBy(() -> tasks.assignees(actor.getId(), request.getId()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> tasks.assign(actor.getId(), request.getId(), input(member)))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThat(items.findByOrganizationIdOrderByCreatedAtDesc(team.getId())).isEmpty();
    }

    @Test
    void aMentorCannotAssignToAnOutsiderAPendingMemberOrAnotherMentor() {
        for (AppUser invalid : List.of(outsider, pending, otherMentor)) {
            assertThatThrownBy(() -> tasks.assign(mentor.getId(), request.getId(), input(invalid)))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThat(items.findByOrganizationIdOrderByCreatedAtDesc(team.getId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MentorshipRequestStatus.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void requestsThatAreNotAcceptedCannotReceiveTasks(MentorshipRequestStatus status) {
        request.setStatus(status);
        assertThatThrownBy(() -> tasks.assignees(mentor.getId(), request.getId()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> tasks.assign(mentor.getId(), request.getId(), input(member)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aRejectedMentorCannotContinueAssigningTasks() {
        mentor.setStatus(UserAccountStatus.REJECTED);
        assertThatThrownBy(() -> tasks.assign(mentor.getId(), request.getId(), input(member)))
                .isInstanceOf(ResponseStatusException.class);
    }

    private AssignMentorshipTaskRequest input(AppUser owner) {
        return new AssignMentorshipTaskRequest(owner.getId(), "Interview users", null, null);
    }

    private AppUser user(String email, UserRole role, Organization organization) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setFullName(email);
        user.setRole(role);
        user.setStatus(UserAccountStatus.APPROVED);
        user.setOrganization(organization);
        entityManager.persist(user);
        return user;
    }
}
