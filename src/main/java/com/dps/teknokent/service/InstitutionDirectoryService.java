package com.dps.teknokent.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.dto.responses.InstitutionPersonSummaryResponse;
import com.dps.teknokent.dto.responses.InstitutionRecordResponse;
import com.dps.teknokent.entity.InstitutionAdvising;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.InstitutionAdvisingRepository;
import com.dps.teknokent.repository.InstitutionPersonRepository;

import lombok.RequiredArgsConstructor;

/** Reads the institution's records about Menton users: their own record, their advisor, an academician's advisees. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstitutionDirectoryService {

	private final InstitutionPersonRepository people;
	private final InstitutionAdvisingRepository advising;

	/** Empty for accounts no institution vouches for (e-mail sign-ups, startups, staff). */
	public List<IdentityKind> kindsOf(UUID userId) {
		return people.findFirstByUserIdOrderBySyncedAtDesc(userId)
				.map(person -> List.copyOf(person.kinds()))
				.orElse(List.of());
	}

	public InstitutionRecordResponse recordOf(UUID userId) {
		InstitutionPerson person = people.findFirstByUserIdOrderBySyncedAtDesc(userId)
				.orElseThrow(() -> new ResourceNotFoundException("No institution record for user " + userId));
		return InstitutionRecordResponse.of(person, advisorOf(person).orElse(null));
	}

	/** The academician's advisees from the institution's records, whether or not they have signed in yet. */
	public List<InstitutionPersonSummaryResponse> adviseesOf(UUID userId) {
		return people.findFirstByUserIdOrderBySyncedAtDesc(userId)
				.filter(InstitutionPerson::isAcademic)
				.map(academician -> people.findByInstitutionAndPersistentIdInOrderByFullNameAsc(
						academician.getInstitution(),
						advising.findByInstitutionAndAdvisorPersistentId(academician.getInstitution(),
								academician.getPersistentId()).stream()
								.map(InstitutionAdvising::getStudentPersistentId)
								.toList()))
				.orElse(List.of())
				.stream()
				.map(InstitutionPersonSummaryResponse::of)
				.toList();
	}

	/**
	 * Whether the institution's record names {@code academicianId} as an advisor of {@code studentId}. Both must have
	 * signed in, the first as an academic, and the advising must be on the same institution's record.
	 */
	public boolean advises(UUID academicianId, UUID studentId) {
		Optional<InstitutionPerson> academician = people.findFirstByUserIdOrderBySyncedAtDesc(academicianId)
				.filter(InstitutionPerson::isAcademic);
		Optional<InstitutionPerson> student = people.findFirstByUserIdOrderBySyncedAtDesc(studentId);
		return academician.isPresent() && student.isPresent()
				&& academician.get().getInstitution().equals(student.get().getInstitution())
				&& advising.existsByInstitutionAndStudentPersistentIdAndAdvisorPersistentId(
						student.get().getInstitution(), student.get().getPersistentId(),
						academician.get().getPersistentId());
	}

	/** A student can have more than one advisor on record; the first by name is shown. */
	private Optional<InstitutionPerson> advisorOf(InstitutionPerson student) {
		List<String> advisorIds = advising
				.findByInstitutionAndStudentPersistentId(student.getInstitution(), student.getPersistentId()).stream()
				.map(InstitutionAdvising::getAdvisorPersistentId)
				.toList();
		if (advisorIds.isEmpty()) {
			return Optional.empty();
		}
		return people.findByInstitutionAndPersistentIdInOrderByFullNameAsc(student.getInstitution(), advisorIds)
				.stream()
				.min(Comparator.comparing(InstitutionPerson::getFullName));
	}
}
