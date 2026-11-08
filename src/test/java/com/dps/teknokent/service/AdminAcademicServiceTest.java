package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.AdminAcademicResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserIdentityRepository;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionAdvisingRepository.AdvisorLoad;
import com.dps.teknokent.repository.InstitutionPersonRepository;

@ExtendWith(MockitoExtension.class)
class AdminAcademicServiceTest {

	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private InstitutionPersonRepository people;
	@Mock
	private InstitutionAdvisingRepository advising;
	@Mock
	private AppUserIdentityRepository identities;
	@InjectMocks
	private AdminAcademicService service;

	@Test
	void listsAcademiciansWithTheirAdviseesAndLastSignIn() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));
		AppUser ali = user(ALI, UserRole.MENTOR);
		InstitutionPerson signedIn = academic("A-1", "Ali Demir", ali);
		InstitutionPerson notYet = academic("A-2", "Zeynep Kara", null);
		notYet.setActive(false);
		when(people.findByAcademicTrueOrderByFullNameAsc()).thenReturn(List.of(signedIn, notYet));
		when(advising.countAdviseesByAdvisor()).thenReturn(List.of(load("A-1", 3), load("A-9", 1)));
		Instant older = Instant.parse("2026-11-01T09:00:00Z");
		Instant newer = Instant.parse("2026-11-07T09:00:00Z");
		when(identities.findByUserIdIn(List.of(ALI)))
				.thenReturn(List.of(identity(ali, older), identity(ali, newer)));

		List<AdminAcademicResponse> rows = service.list(ADMIN);

		assertThat(rows).extracting(AdminAcademicResponse::fullName).containsExactly("Ali Demir", "Zeynep Kara");
		AdminAcademicResponse first = rows.get(0);
		assertThat(first.userId()).isEqualTo(ALI);
		assertThat(first.signedIn()).isTrue();
		assertThat(first.lastLoginAt()).isEqualTo(newer);
		assertThat(first.adviseeCount()).isEqualTo(3);
		assertThat(first.departmentName()).isEqualTo("Bilgisayar Mühendisliği");
		AdminAcademicResponse second = rows.get(1);
		assertThat(second.userId()).isNull();
		assertThat(second.signedIn()).isFalse();
		assertThat(second.lastLoginAt()).isNull();
		assertThat(second.adviseeCount()).isZero();
		assertThat(second.active()).isFalse();
	}

	@Test
	void anAcademicListWithNobodySignedInSkipsTheSignInLookup() {
		when(domainLookupService.requireUser(ADMIN)).thenReturn(user(ADMIN, UserRole.ADMIN));
		when(people.findByAcademicTrueOrderByFullNameAsc()).thenReturn(List.of(academic("A-2", "Zeynep Kara", null)));
		when(advising.countAdviseesByAdvisor()).thenReturn(List.of());

		assertThat(service.list(ADMIN)).hasSize(1);
		verify(identities, never()).findByUserIdIn(any());
	}

	@Test
	void onlyAnAdminListsAcademicians() {
		UUID coordinator = UUID.randomUUID();
		when(domainLookupService.requireUser(coordinator)).thenReturn(user(coordinator, UserRole.COORDINATOR));

		assertThatThrownBy(() -> service.list(coordinator)).isInstanceOf(InvalidRequestException.class);
		verify(people, never()).findByAcademicTrueOrderByFullNameAsc();
	}

	private static InstitutionPerson academic(String persistentId, String name, AppUser user) {
		InstitutionPerson person = new InstitutionPerson("odtu", persistentId);
		person.setId(UUID.randomUUID());
		person.setFullName(name);
		person.setAcademic(true);
		person.setAcademicTitle("Doç. Dr.");
		person.setFaculty("Mühendislik Fakültesi");
		person.setDepartmentCode("CENG");
		person.setDepartmentName("Bilgisayar Mühendisliği");
		person.setSource(InstitutionDataSource.IMPORT);
		person.setSyncedAt(Instant.now());
		person.setUser(user);
		return person;
	}

	private static AdvisorLoad load(String advisor, long advisees) {
		return new AdvisorLoad() {
			@Override
			public String getInstitution() {
				return "odtu";
			}

			@Override
			public String getAdvisorPersistentId() {
				return advisor;
			}

			@Override
			public long getAdvisees() {
				return advisees;
			}
		};
	}

	private static AppUserIdentity identity(AppUser user, Instant lastLogin) {
		AppUserIdentity identity = new AppUserIdentity(user, "ODTU", "A-1", lastLogin);
		identity.setLastLoginAt(lastLogin);
		return identity;
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		return user;
	}
}
