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

	@Test
	void anAcademicianAdvisesOnlyTheStudentsTheRecordGivesThem() {
		UUID academicianId = UUID.randomUUID();
		UUID adviseeId = UUID.randomUUID();
		UUID otherStudentId = UUID.randomUUID();
		when(people.findFirstByUserIdOrderBySyncedAtDesc(academicianId))
				.thenReturn(Optional.of(person("a1", "Ahmet", Set.of(IdentityKind.ACADEMIC), academicianId)));
		when(people.findFirstByUserIdOrderBySyncedAtDesc(adviseeId))
				.thenReturn(Optional.of(person("s1", "Ayşe", Set.of(IdentityKind.STUDENT), adviseeId)));
		when(people.findFirstByUserIdOrderBySyncedAtDesc(otherStudentId))
				.thenReturn(Optional.of(person("s2", "Can", Set.of(IdentityKind.STUDENT), otherStudentId)));
		when(advising.existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId("odtu", "s1", "a1"))
				.thenReturn(true);

		assertThat(service.advises(academicianId, adviseeId)).isTrue();
		assertThat(service.advises(academicianId, otherStudentId)).isFalse();
		assertThat(service.advises(academicianId, UUID.randomUUID())).isFalse();
	}

	@Test
	void noOneButASignedInAcademicianOfTheSameInstitutionAdvises() {
		UUID studentId = UUID.randomUUID();
		UUID adviseeId = UUID.randomUUID();
		UUID elsewhereId = UUID.randomUUID();
		when(people.findFirstByUserIdOrderBySyncedAtDesc(studentId))
				.thenReturn(Optional.of(person("a1", "Ayşe", Set.of(IdentityKind.STUDENT), studentId)));
		when(people.findFirstByUserIdOrderBySyncedAtDesc(adviseeId))
				.thenReturn(Optional.of(person("s1", "Can", Set.of(IdentityKind.STUDENT), adviseeId)));
		InstitutionPerson elsewhere = new InstitutionPerson("other", "a1");
		elsewhere.setFullName("Ahmet");
		elsewhere.setKinds(Set.of(IdentityKind.ACADEMIC));
		elsewhere.setSyncedAt(Instant.now());
		when(people.findFirstByUserIdOrderBySyncedAtDesc(elsewhereId)).thenReturn(Optional.of(elsewhere));
		when(advising.existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId("odtu", "s1", "a1"))
				.thenReturn(true);

		// A student whose persistent id happens to match an advisor's is still no academician.
		assertThat(service.advises(studentId, adviseeId)).isFalse();
		// The same persistent id at another institution is someone else.
		assertThat(service.advises(elsewhereId, adviseeId)).isFalse();
		assertThat(service.advises(UUID.randomUUID(), adviseeId)).isFalse();
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
