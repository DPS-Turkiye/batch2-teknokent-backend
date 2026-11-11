package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.AppUserIdentity;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.repository.InstitutionAdvisingRepository.AdvisorLoad;

import jakarta.persistence.EntityManager;

@DataJpaTest
class AcademicDirectoryQueryTest {

	private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

	@Autowired
	private InstitutionPersonRepository people;
	@Autowired
	private InstitutionAdvisingRepository advising;
	@Autowired
	private AppUserIdentityRepository identities;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listsOnlyAcademiciansByNameAndCountsTheAdviseesTheInstitutionHasARecordFor() {
		person("A-2", "Zeynep Kara", false, true);
		person("A-1", "Ali Demir", false, true);
		person("S-1", "Ayşe Yılmaz", true, false);
		person("S-2", "Mehmet Kaya", true, false);
		person("S-3", "Zeynep Demir", true, false);
		advising.save(new InstitutionAdvising("odtu", "S-1", "A-1", NOW));
		advising.save(new InstitutionAdvising("odtu", "S-2", "A-1", NOW));
		advising.save(new InstitutionAdvising("odtu", "S-3", "A-2", NOW));
		advising.save(new InstitutionAdvising("odtu", "S-9", "A-2", NOW));
		entityManager.flush();
		entityManager.clear();

		assertThat(people.findByAcademicTrueOrderByFullNameAsc())
				.extracting(InstitutionPerson::getFullName)
				.containsExactly("Ali Demir", "Zeynep Kara");
		Map<String, Long> loads = advising.countAdviseesByAdvisor().stream()
				.collect(Collectors.toMap(AdvisorLoad::getAdvisorPersistentId, AdvisorLoad::getAdvisees));
		assertThat(loads).containsExactlyInAnyOrderEntriesOf(Map.of("A-1", 2L, "A-2", 1L));
	}

	@Test
	void findsTheSignInsOfTheGivenUsers() {
		AppUser signedIn = user("ali.demir@metu.edu.tr");
		AppUser other = user("other@metu.edu.tr");
		entityManager.persist(new AppUserIdentity(signedIn, "ODTU", "A-1", NOW));
		entityManager.persist(new AppUserIdentity(other, "ODTU", "A-9", NOW));
		entityManager.flush();
		entityManager.clear();

		List<AppUserIdentity> found = identities.findByUserIdIn(List.of(signedIn.getId()));

		assertThat(found).hasSize(1);
		assertThat(found.get(0).getPersistentId()).isEqualTo("A-1");
	}

	private void person(String persistentId, String name, boolean student, boolean academic) {
		InstitutionPerson person = new InstitutionPerson("odtu", persistentId);
		person.setFullName(name);
		person.setStudent(student);
		person.setAcademic(academic);
		person.setSource(InstitutionDataSource.IMPORT);
		person.setSyncedAt(NOW);
		entityManager.persist(person);
	}

	private AppUser user(String email) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(UserRole.MENTOR);
		entityManager.persist(user);
		return user;
	}
}
