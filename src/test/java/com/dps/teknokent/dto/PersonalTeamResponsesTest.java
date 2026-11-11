package com.dps.teknokent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.TeamHistoryResponse;
import com.dps.teknokent.dto.responses.UserResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.TeamMembership;

/** A student's personal team never reaches the web app as a startup: no name, no team history entry. */
class PersonalTeamResponsesTest {

	@Test
	void meCarriesThePersonalTeamsIdButNoNameAndFlagsIt() {
		AppUser student = user();
		Organization personal = organization("Ayşe Yılmaz", true);
		student.setOrganization(personal);

		UserResponse me = UserResponse.fromEntity(student);

		assertThat(me.organizationId()).isEqualTo(personal.getId());
		assertThat(me.organizationName()).isNull();
		assertThat(me.personalOrganization()).isTrue();
	}

	@Test
	void aRealStartupKeepsItsName() {
		AppUser founder = user();
		founder.setOrganization(organization("Flexvision", false));

		UserResponse me = UserResponse.fromEntity(founder);

		assertThat(me.organizationName()).isEqualTo("Flexvision");
		assertThat(me.personalOrganization()).isFalse();
	}

	@Test
	void teamHistoryLeavesOutThePersonalTeam() {
		AppUser student = user();
		TeamMembership personalStay = stay(organization("Ayşe Yılmaz", true));
		TeamMembership startupStay = stay(organization("Flexvision", false));

		TeamHistoryResponse history = TeamHistoryResponse.of(student, List.of(personalStay, startupStay));

		assertThat(history.stays()).extracting(TeamHistoryResponse.Stay::organizationName)
				.containsExactly("Flexvision");
	}

	private static AppUser user() {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName("Ayşe Yılmaz");
		user.setEmail("ayse@odtu-dev.invalid");
		user.setStatus(UserAccountStatus.APPROVED);
		user.setPrimaryRole(UserRole.MENTEE);
		return user;
	}

	private static Organization organization(String name, boolean personal) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		organization.setPersonal(personal);
		return organization;
	}

	private static TeamMembership stay(Organization organization) {
		TeamMembership membership = new TeamMembership();
		membership.setOrganization(organization);
		membership.setJoinedAt(Instant.now());
		return membership;
	}
}
