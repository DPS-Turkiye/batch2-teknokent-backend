package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;

class InstitutionDirectoryServiceTest {

	private final InstitutionPersonRepository people = mock(InstitutionPersonRepository.class);
	private final InstitutionAdvisingRepository advising = mock(InstitutionAdvisingRepository.class);
	private final InstitutionDirectoryService service = new InstitutionDirectoryService(people, advising);

	@Test
	void theRecordNamesTheAdvisorEvenBeforeTheyHaveSignedIn() {
		UUID studentId = UUID.randomUUID();
		InstitutionPerson student = person("s1", "Ayşe", Set.of(IdentityKind.STUDENT), studentId);
		InstitutionPerson advisor = person("a1", "Ahmet", Set.of(IdentityKind.ACADEMIC), null);
		advisor.setAcademicTitle("Prof. Dr.");
		when(people.findFirstByUserIdOrderBySyncedAtDesc(studentId)).thenReturn(Optional.of(student));
		when(advising.findByInstitutionAndStudentPersistentId("odtu", "s1"))
				.thenReturn(List.of(new InstitutionAdvising("odtu", "s1", "a1", Instant.now())));
		when(people.findByInstitutionAndPersistentIdInOrderByFullNameAsc(eq("odtu"), anyCollection()))
				.thenReturn(List.of(advisor));

		var record = service.recordOf(studentId);

		assertThat(record.kinds()).containsExactly(IdentityKind.STUDENT);
		assertThat(record.advisor().fullName()).isEqualTo("Ahmet");
		assertThat(record.advisor().academicTitle()).isEqualTo("Prof. Dr.");
		assertThat(record.advisor().userId()).isNull();
	}

	@Test
	void anAccountWithoutARecordHasNone() {
		UUID userId = UUID.randomUUID();
		when(people.findFirstByUserIdOrderBySyncedAtDesc(userId)).thenReturn(Optional.empty());

		assertThat(service.kindsOf(userId)).isEmpty();
		assertThatThrownBy(() -> service.recordOf(userId)).isInstanceOf(ResourceNotFoundException.class);
		assertThat(service.adviseesOf(userId)).isEmpty();
	}

	@Test
	void anAcademicianSeesTheirAdvisees() {
		UUID academicianId = UUID.randomUUID();
		when(people.findFirstByUserIdOrderBySyncedAtDesc(academicianId))
				.thenReturn(Optional.of(person("a1", "Ahmet", Set.of(IdentityKind.ACADEMIC), academicianId)));
		when(advising.findByInstitutionAndAdvisorPersistentId("odtu", "a1"))
				.thenReturn(List.of(new InstitutionAdvising("odtu", "s1", "a1", Instant.now())));
		when(people.findByInstitutionAndPersistentIdInOrderByFullNameAsc("odtu", List.of("s1")))
				.thenReturn(List.of(person("s1", "Ayşe", Set.of(IdentityKind.STUDENT), null)));

		assertThat(service.adviseesOf(academicianId)).singleElement()
				.satisfies(advisee -> assertThat(advisee.fullName()).isEqualTo("Ayşe"));
	}

	// A student's own record never lists anyone as their advisee.
	@Test
	void aStudentHasNoAdvisees() {
		UUID studentId = UUID.randomUUID();
		when(people.findFirstByUserIdOrderBySyncedAtDesc(studentId))
				.thenReturn(Optional.of(person("s1", "Ayşe", Set.of(IdentityKind.STUDENT), studentId)));

		assertThat(service.adviseesOf(studentId)).isEmpty();
	}

	private static InstitutionPerson person(String persistentId, String name, Set<IdentityKind> kinds, UUID userId) {
		InstitutionPerson person = new InstitutionPerson("odtu", persistentId);
		person.setFullName(name);
		person.setKinds(kinds);
		person.setSyncedAt(Instant.now());
		if (userId != null) {
			AppUser user = new AppUser();
			user.setId(userId);
			person.setUser(user);
		}
		return person;
	}
}
