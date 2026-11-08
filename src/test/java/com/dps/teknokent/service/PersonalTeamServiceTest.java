package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.OrganizationRepository;

@ExtendWith(MockitoExtension.class)
class PersonalTeamServiceTest {

	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private TeamMembershipService teamMembershipService;
	@InjectMocks
	private PersonalTeamService service;

	@Test
	void aStudentWithoutATeamGetsAHiddenTeamOfOneNamedAfterThem() {
		AppUser student = student("Ayşe Yılmaz");

		service.ensureFor(student);

		Organization team = student.getOrganization();
		assertThat(team).isNotNull();
		assertThat(team.isPersonal()).isTrue();
		assertThat(team.getName()).isEqualTo("Ayşe Yılmaz");
		assertThat(team.getFounder()).isSameAs(student);
		assertThat(Organization.visibleName(team)).isNull();
		verify(organizationRepository).saveAndFlush(team);
		verify(teamMembershipService).joined(student, team);
	}

	@Test
	void aSecondSignInKeepsTheSameTeam() {
		AppUser student = student("Ayşe Yılmaz");
		service.ensureFor(student);
		Organization first = student.getOrganization();

		service.ensureFor(student);

		assertThat(student.getOrganization()).isSameAs(first);
		verify(organizationRepository).saveAndFlush(any());
	}

	@Test
	void thePersonalTeamFollowsTheStudentsName() {
		AppUser student = student("Ayşe Yılmaz");
		service.ensureFor(student);
		student.setFullName("Ayşe Yılmaz Kaya");

		service.ensureFor(student);

		assertThat(student.getOrganization().getName()).isEqualTo("Ayşe Yılmaz Kaya");
	}

	// D6: a startup member who is also an ODTÜ student keeps their startup; no second team is made.
	@Test
	void aStudentInARealStartupKeepsIt() {
		AppUser founder = student("Deniz Kaya");
		Organization startup = new Organization();
		startup.setName("Flexvision");
		founder.setOrganization(startup);

		service.ensureFor(founder);

		assertThat(founder.getOrganization()).isSameAs(startup);
		assertThat(startup.isPersonal()).isFalse();
		assertThat(startup.getName()).isEqualTo("Flexvision");
		verify(organizationRepository, never()).saveAndFlush(any());
		verify(teamMembershipService, never()).joined(any(), any());
	}

	private static AppUser student(String fullName) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setFullName(fullName);
		user.setEmail("ayse@odtu-dev.invalid");
		return user;
	}
}
