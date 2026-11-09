package com.dps.teknokent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Gives an ODTÜ student a hidden team of one, so mentorship requests, meetings, mentorships and session hours (all
 * keyed by organization) work for them unchanged. It is made lazily, the first time the student does something that
 * needs a team (their first peer-mentoring request), never at sign-in. Like an İŞKUR individual team it is named after
 * the student, so any sentence that names the team names the person; unlike one it is marked {@code personal}, joins
 * no program as a startup (its program is the student's own, see MenteeProgramService), and is never listed as a team
 * or shown as a startup.
 */
@Service
@RequiredArgsConstructor
public class PersonalTeamService {

	private final OrganizationRepository organizationRepository;
	private final TeamMembershipService teamMembershipService;

	/**
	 * The team to file the student's mentoring under. Idempotent: an account that already has a team (its own startup,
	 * or an earlier personal team) keeps it; a personal team follows the student's name.
	 */
	@Transactional
	public Organization ensureFor(AppUser user) {
		Organization current = user.getOrganization();
		if (current != null) {
			if (current.isPersonal() && !user.getFullName().equals(current.getName())) {
				current.setName(user.getFullName());
				organizationRepository.save(current);
			}
			return current;
		}
		Organization personal = new Organization();
		personal.setName(user.getFullName());
		personal.setPersonal(true);
		personal.setFounder(user);
		organizationRepository.saveAndFlush(personal);
		user.setOrganization(personal);
		teamMembershipService.joined(user, personal);
		return personal;
	}
}
