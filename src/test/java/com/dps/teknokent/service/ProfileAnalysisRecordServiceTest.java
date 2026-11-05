package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.dps.teknokent.domain.ProfileAnalysisRecordScope;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProfileAnalysis;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.exception.InvalidRequestException;
import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class ProfileAnalysisRecordServiceTest {
    @Autowired private ProfileAnalysisRecordService records;
    @Autowired private EntityManager em;
    private Organization team;
    private AppUser actor;

    @BeforeEach
    void setup() {
        team = new Organization(); team.setName("Record Team"); em.persist(team);
        actor = user("record-actor@flow.test", team, UserRole.MENTEE);
    }

    @Test
    void newerStartupUploadsDoNotHidePersonalOrHistoricalTeamCvRecords() {
        var historical = record(team.getId(), null, ProfileAnalysisRecordScope.TEAM_PERSONAL, 1);
        var own = record(team.getId(), actor.getId(), ProfileAnalysisRecordScope.PERSONAL, 2);
        var firstStartup = record(team.getId(), null, ProfileAnalysisRecordScope.STARTUP, 3);
        var startup = record(team.getId(), null, ProfileAnalysisRecordScope.STARTUP, 4);
        em.flush(); em.clear();
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.PERSONAL).id()).isEqualTo(own.getId());
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.TEAM_PERSONAL).id()).isEqualTo(historical.getId());
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP).id()).isEqualTo(startup.getId()).isNotEqualTo(firstStartup.getId());
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP).profile()).contains("confirmed summary");
    }

    @Test
    void readsCannotReturnAnotherMembersOrAnotherStartupsPersonalAnalysis() {
        var own = record(team.getId(), actor.getId(), ProfileAnalysisRecordScope.PERSONAL, 1);
        var member = user("record-other@flow.test", team, UserRole.MENTEE);
        record(team.getId(), member.getId(), ProfileAnalysisRecordScope.PERSONAL, 3);
        var other = new Organization(); other.setName("Other Record Team"); em.persist(other);
        record(other.getId(), null, ProfileAnalysisRecordScope.TEAM_PERSONAL, 4);
        record(other.getId(), actor.getId(), ProfileAnalysisRecordScope.PERSONAL, 5);
        em.flush(); em.clear();
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.PERSONAL).id()).isEqualTo(own.getId());
        assertThatThrownBy(() -> records.latest(actor.getId(), ProfileAnalysisRecordScope.TEAM_PERSONAL)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void onlyApprovedStartupMembersCanReadRecords() {
        record(team.getId(), null, ProfileAnalysisRecordScope.STARTUP, 1);
        actor.setStatus(UserAccountStatus.PENDING);
        assertThatThrownBy(() -> records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP)).isInstanceOf(ResponseStatusException.class);
        actor.setStatus(UserAccountStatus.APPROVED); actor.setRole(UserRole.MENTOR);
        assertThatThrownBy(() -> records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP)).isInstanceOf(ResponseStatusException.class);
        actor.setRole(UserRole.MENTEE); actor.setOrganization(null);
        assertThatThrownBy(() -> records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anEmptyScopeReturnsNotFoundWithoutWritingARecord() {
        assertThatThrownBy(() -> records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void manualCreationUsesTheApprovedCallersStartupAndPreservesOlderCvEvidence() {
        var previous = record(team.getId(), null, ProfileAnalysisRecordScope.TEAM_PERSONAL, 1);
        var created = records.createStartup(actor.getId(), Map.of("startup_name", "Manual Startup", "technologies", List.of("Java")));
        em.flush(); em.clear();
        assertThat(created.organizationId()).isEqualTo(team.getId());
        assertThat(created.appUserId()).isNull();
        assertThat(created.profileType()).isEqualTo(ProfileAnalysisRecordScope.STARTUP.profileType());
        assertThat(created.status()).isEqualTo("CONFIRMED");
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP).id()).isEqualTo(created.id());
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.TEAM_PERSONAL).id()).isEqualTo(previous.getId());
    }

    @Test
    void manualCreationRejectsEmptyProfilesAndUnapprovedActors() {
        assertThatThrownBy(() -> records.createStartup(actor.getId(), Map.of())).isInstanceOf(InvalidRequestException.class);
        actor.setStatus(UserAccountStatus.REJECTED);
        assertThatThrownBy(() -> records.createStartup(actor.getId(), Map.of("startup_name", "Denied"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void confirmationPersistsOnlyForAnApprovedMembersOwnStartupRecord() {
        var draft = record(team.getId(), null, ProfileAnalysisRecordScope.STARTUP, 1);
        draft.setStatus("DRAFT"); draft.setConfirmedProfile(null);
        var confirmed = records.confirmStartup(actor.getId(), draft.getId(), Map.of("startup_name", "Reviewed Startup", "technologies", List.of("Java")));
        em.flush(); em.clear();
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(records.latest(actor.getId(), ProfileAnalysisRecordScope.STARTUP).profile()).contains("Reviewed Startup");
    }

    @Test
    void confirmationCannotRewriteAnotherStartupOrAnyPersonalRecord() {
        var other = new Organization(); other.setName("Unrelated Confirmation Team"); em.persist(other);
        var foreign = record(other.getId(), null, ProfileAnalysisRecordScope.STARTUP, 1);
        var personal = record(team.getId(), actor.getId(), ProfileAnalysisRecordScope.PERSONAL, 2);
        var teamPersonal = record(team.getId(), null, ProfileAnalysisRecordScope.TEAM_PERSONAL, 3);
        for (var target : List.of(foreign, personal, teamPersonal)) {
            assertThatThrownBy(() -> records.confirmStartup(actor.getId(), target.getId(), Map.of("startup_name", "Denied")))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(target.getConfirmedProfile()).doesNotContain("Denied");
        }
    }

    private ProfileAnalysis record(UUID organization, UUID owner, ProfileAnalysisRecordScope scope, int seconds) {
        var now = Instant.parse("2026-11-03T10:00:00Z").plusSeconds(seconds);
        var result = ProfileAnalysis.fromParsedProfile(organization, owner, "saved.pdf", scope.profileType(), "{}", "{}", "{}", now);
        result.confirm("{\"startup_summary\":\"confirmed summary\"}", now);
        em.persist(result);
        return result;
    }

    private AppUser user(String email, Organization organization, UserRole role) {
        var result = new AppUser(); result.setEmail(email); result.setFullName(email);
        result.setRole(role); result.setStatus(UserAccountStatus.APPROVED); result.setOrganization(organization); em.persist(result);
        return result;
    }
}
