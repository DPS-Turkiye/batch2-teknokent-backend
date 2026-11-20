package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.TeamPersonRole;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.ProgramTeamPeopleResponse.Person;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramTeamPeopleServiceTest {

	@Mock
	private CoordinatorProgramService programService;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private AppUserRepository appUserRepository;
	@InjectMocks
	private ProgramTeamPeopleService service;

	private static final UUID ELIF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID PROGRAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	@Test
	void findsFoundersAndMembersByNameIgnoringTurkishLettersEachWithTheirTeam() {
		AppUser cagri = user("Çağrı Yılmaz", "CEO");
		Organization atlas = team("Atlas AI", cagri);
		AppUser irem = member("İrem Cağlar", atlas);
		AppUser mert = member("Mert Demir", atlas);
		member(cagri, atlas);
		AppUser deniz = user("Deniz Çağan", null);
		Organization mira = team("Mira Bio", deniz);
		inProgram(atlas, mira);
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(Set.of(atlas.getId(), mira.getId())))
				.thenReturn(List.of(cagri, irem, mert));

		var result = service.search(ELIF, PROGRAM, " CAG ");

		assertThat(result.truncated()).isFalse();
		assertThat(result.people()).extracting(Person::fullName, Person::role, Person::teamName, Person::jobTitle)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("Çağrı Yılmaz", TeamPersonRole.FOUNDER, "Atlas AI", "CEO"),
						org.assertj.core.groups.Tuple.tuple("Deniz Çağan", TeamPersonRole.FOUNDER, "Mira Bio", null),
						org.assertj.core.groups.Tuple.tuple("İrem Cağlar", TeamPersonRole.MEMBER, "Atlas AI", null));
		assertThat(result.people()).extracting(Person::organizationId)
				.containsExactly(atlas.getId(), mira.getId(), atlas.getId());
	}

	@Test
	void everyWordOfTheQueryHasToMatch() {
		AppUser cagri = user("Çağrı Yılmaz", null);
		Organization atlas = team("Atlas AI", cagri);
		inProgram(atlas);

		assertThat(service.search(ELIF, PROGRAM, "cagri yil").people()).hasSize(1);
		assertThat(service.search(ELIF, PROGRAM, "cagri demir").people()).isEmpty();
	}

	@Test
	void leavesOutTeamsThatMayNotTakePartAndPeopleOfOtherTeams() {
		AppUser pending = user("Çağrı Bekler", null);
		pending.setStatus(UserAccountStatus.PENDING);
		Organization waiting = team("Bekleyen", pending);
		Organization archived = team("Eski", user("Çağrı Eski", null));
		archived.setArchivedAt(Instant.parse("2026-11-01T09:00:00Z"));
		Organization atlas = team("Atlas AI", user("Mert Demir", null));
		Organization outside = team("Dışarıda", null);
		AppUser stray = member("Çağrı Başka", outside);
		AppUser teamless = user("Çağrı Takımsız", null);
		inProgram(waiting, archived, atlas);
		when(appUserRepository.findByOrganizationIdInOrderByFullNameAsc(anyCollection()))
				.thenReturn(List.of(stray, teamless));

		var result = service.search(ELIF, PROGRAM, "cagri");

		assertThat(result.people()).isEmpty();
	}

	@Test
	void namesakesAreOrderedByTheirTeam() {
		inProgram(team("Zeytin", user("Ayşe Kaya", null)), team("Atlas AI", user("Ayşe Kaya", null)));

		assertThat(service.search(ELIF, PROGRAM, "ayse").people()).extracting(Person::teamName)
				.containsExactly("Atlas AI", "Zeytin");
	}

	@Test
	void anEmptyProgramFindsNobodyWithoutLookingUpPeople() {
		inProgram();

		assertThat(service.search(ELIF, PROGRAM, "cagri").people()).isEmpty();
		verifyNoInteractions(appUserRepository);
	}

	@Test
	void capsTheListAndSaysWhenMorePeopleMatched() {
		List<Organization> teams = new ArrayList<>();
		IntStream.range(0, ProgramTeamPeopleService.MAX_RESULTS + 1)
				.forEach(index -> teams.add(team("Takım " + index, user("Ayşe " + index, null))));
		inProgram(teams.toArray(Organization[]::new));

		var result = service.search(ELIF, PROGRAM, "ayse");

		assertThat(result.people()).hasSize(ProgramTeamPeopleService.MAX_RESULTS);
		assertThat(result.truncated()).isTrue();
	}

	@Test
	void refusesAQueryThatIsTooShortOrTooLong() {
		assertThatThrownBy(() -> service.search(ELIF, PROGRAM, " ç "))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.search(ELIF, PROGRAM, "a".repeat(101)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.search(ELIF, PROGRAM, " ".repeat(201) + "ayşe"))
				.isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(programStartupRepository, appUserRepository);
	}

	@Test
	void aCoordinatorWhoDoesNotRunTheProgramSearchesNothing() {
		when(programService.requireManagedProgram(ELIF, PROGRAM))
				.thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> service.search(ELIF, PROGRAM, "cagri"))
				.isInstanceOf(ResponseStatusException.class);
		verifyNoInteractions(programStartupRepository, organizationRepository, appUserRepository);
	}

	private void inProgram(Organization... teams) {
		List<ProgramStartup> startups = new ArrayList<>();
		for (Organization team : teams) {
			ProgramStartup startup = new ProgramStartup();
			startup.setOrganizationId(team.getId());
			startups.add(startup);
		}
		when(programStartupRepository.findByProgramIdIn(List.of(PROGRAM))).thenReturn(startups);
		when(organizationRepository.findAllById(startups.stream().map(ProgramStartup::getOrganizationId).toList()))
				.thenReturn(List.of(teams));
	}

	private static Organization team(String name, AppUser founder) {
		Organization organization = new Organization();
		organization.setId(UUID.randomUUID());
		organization.setName(name);
		organization.setFounder(founder);
		organization.setApprovalStatus(UserAccountStatus.APPROVED);
		return organization;
	}

	private static AppUser member(String fullName, Organization team) {
		AppUser user = user(fullName, null);
		member(user, team);
		return user;
	}

	private static void member(AppUser user, Organization team) {
		user.setOrganization(team);
	}

	private static AppUser user(String fullName, String jobTitle) {
		AppUser user = new AppUser();
		user.setId(UUID.randomUUID());
		user.setRole(UserRole.MENTEE);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setFullName(fullName);
		user.setJobTitle(jobTitle);
		return user;
	}
}
