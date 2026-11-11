package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MentorLanguage;
import com.dps.teknokent.domain.PersonProgramRelation;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;

/**
 * Who a mentor or mentee is and what they do, for the admin and coordinator profile page. Phone numbers and
 * contact preferences stay out. {@code mentor} is null without a mentor profile, {@code startup} without a
 * (non-personal) startup.
 */
public record PersonSummaryResponse(
		UUID id,
		String fullName,
		String email,
		UserRole role,
		UserAccountStatus status,
		String jobTitle,
		String bio,
		String linkedinUrl,
		List<String> expertise,
		Instant createdAt,
		Mentor mentor,
		Startup startup,
		List<Program> programs) {

	public record Mentor(
			String professionalTitle,
			String company,
			String about,
			String experienceLevel,
			String sectorExperience,
			String startupStages,
			List<String> specialties,
			List<String> offerings,
			List<MentorLanguage> languages,
			List<MentorExperienceResponse> experiences,
			List<MentorEducationResponse> educations,
			Integer menteeCapacity,
			String meetingMode,
			long activeMenteeCount,
			long completedMentorshipCount) {

		public static Mentor of(MentorProfileResponse profile) {
			return new Mentor(profile.professionalTitle(), profile.company(), profile.about(),
					profile.experienceLevel(), profile.sectorExperience(), profile.startupStages(),
					profile.specialties(), profile.offerings(), profile.languages(), profile.experiences(),
					profile.educations(), profile.menteeCapacity(), profile.meetingMode(),
					profile.activeMenteeCount(), profile.completedMentorshipCount());
		}
	}

	public record Startup(
			UUID id,
			String name,
			String description,
			String website,
			String sector,
			String maturity,
			String problem,
			String solution,
			String fundingStage,
			Integer foundedYear) {

		public static Startup of(Organization organization) {
			return new Startup(organization.getId(), organization.getName(), organization.getDescription(),
					organization.getWebsite(),
					organization.getSector() == null ? null : organization.getSector().value(),
					organization.getMaturity() == null ? null : organization.getMaturity().value(),
					organization.getProblem(), organization.getSolution(),
					organization.getFundingStage() == null ? null : organization.getFundingStage().value(),
					organization.getFoundedYear());
		}
	}

	/** {@code mentorStatus} is set for {@link PersonProgramRelation#MENTOR} rows only. */
	public record Program(UUID id, String name, ProgramStatus status, PersonProgramRelation relation,
			ProgramMentorStatus mentorStatus) {
	}

	public static PersonSummaryResponse of(AppUser person, Mentor mentor, Startup startup, List<Program> programs) {
		return new PersonSummaryResponse(person.getId(), person.getFullName(), person.getEmail(), person.getRole(),
				person.getStatus(), person.getJobTitle(), person.getBio(), person.getLinkedinUrl(),
				List.copyOf(person.getExpertise()), person.getCreatedAt(), mentor, startup, programs);
	}
}
