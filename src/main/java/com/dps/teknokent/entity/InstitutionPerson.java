package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.InstitutionDataSource;
import com.dps.teknokent.domain.StudyLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the institution says about a person: read-only in Menton, refreshed at sign-in or by an import. Kept apart
 * from {@link AppUser} so the user's own profile edits and the institution's record never overwrite each other.
 */
@Entity
@Table(name = "institution_person",
		uniqueConstraints = @UniqueConstraint(name = "uq_institution_person", columnNames = { "institution", "persistent_id" }))
@Getter
@Setter
@NoArgsConstructor
public class InstitutionPerson {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 16)
	private String institution;

	@Column(name = "persistent_id", nullable = false, length = 120)
	private String persistentId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private AppUser user;

	@Column(nullable = false)
	private boolean student;

	@Column(nullable = false)
	private boolean academic;

	@Column(nullable = false)
	private boolean staff;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	private String email;

	private String faculty;

	@Column(name = "department_code", length = 32)
	private String departmentCode;

	@Column(name = "department_name")
	private String departmentName;

	@Column(name = "class_year")
	private Integer classYear;

	@Enumerated(EnumType.STRING)
	@Column(name = "program_level", length = 16)
	private StudyLevel programLevel;

	@Column(name = "academic_title", length = 64)
	private String academicTitle;

	@Column(name = "iskur_participant", nullable = false)
	private boolean iskurParticipant;

	@Column(nullable = false)
	private boolean active = true;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private InstitutionDataSource source;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	public InstitutionPerson(String institution, String persistentId) {
		this.institution = institution;
		this.persistentId = persistentId;
	}

	public Set<IdentityKind> kinds() {
		Set<IdentityKind> kinds = EnumSet.noneOf(IdentityKind.class);
		if (student) {
			kinds.add(IdentityKind.STUDENT);
		}
		if (academic) {
			kinds.add(IdentityKind.ACADEMIC);
		}
		if (staff) {
			kinds.add(IdentityKind.STAFF);
		}
		return kinds;
	}

	public void setKinds(Set<IdentityKind> kinds) {
		student = kinds.contains(IdentityKind.STUDENT);
		academic = kinds.contains(IdentityKind.ACADEMIC);
		staff = kinds.contains(IdentityKind.STAFF);
	}
}
