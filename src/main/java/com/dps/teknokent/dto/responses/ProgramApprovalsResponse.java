package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;

/** The coordinator's "Onaylar" tab: people and startups of one program waiting for a decision. */
/** mentors: mentors who asked to join the program; teamChanges: leave and removal requests waiting in it. */
public record ProgramApprovalsResponse(
		List<Person> people, List<Person> mentors, List<Startup> startups, List<TeamChangeResponse> teamChanges) {

	/** organizationName is null for someone who signed up without a startup. */
	public record Person(UUID id, String fullName, String email, String jobTitle, String organizationName,
			Instant createdAt) {

		public static Person of(AppUser user) {
			return new Person(user.getId(), user.getFullName(), user.getEmail(), user.getJobTitle(),
					user.getOrganization() == null ? null : user.getOrganization().getName(), user.getCreatedAt());
		}
	}

	public record Startup(UUID id, String name, String description, String founderName, String founderEmail,
			Instant createdAt) {

		public static Startup of(Organization organization) {
			AppUser founder = organization.getFounder();
			return new Startup(organization.getId(), organization.getName(), organization.getDescription(),
					founder == null ? null : founder.getFullName(), founder == null ? null : founder.getEmail(),
					organization.getCreatedAt());
		}
	}
}
