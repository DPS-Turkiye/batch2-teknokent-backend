package com.dps.teknokent.service;

import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.TeamPersonRole;
import com.dps.teknokent.dto.responses.ProgramTeamPeopleResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.NameSearch;

import lombok.RequiredArgsConstructor;

/**
 * Takımlar's "Kişi ara": finds a founder or team member by name among the teams the program lists, the same teams
 * and people the Takımlar rows and drawers already show a coordinator of the program. No e-mail addresses, and a
 * short list at a time.
 */
@Service
@RequiredArgsConstructor
public class ProgramTeamPeopleService {

	static final int MIN_QUERY_LENGTH = 2;
	static final int MAX_QUERY_LENGTH = 100;
	static final int MAX_RESULTS = 50;

	private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr"));

	private final CoordinatorProgramService programService;
	private final ProgramStartupRepository programStartupRepository;
	private final OrganizationRepository organizationRepository;
	private final AppUserRepository appUserRepository;

	@Transactional(readOnly = true)
	public ProgramTeamPeopleResponse search(UUID actorId, UUID programId, String query) {
		programService.requireManagedProgram(actorId, programId);
		// The raw cap keeps an oversized query from being folded at all; folding only shortens the text.
		String folded = query.length() > 2 * MAX_QUERY_LENGTH ? query : NameSearch.fold(query);
		if (folded.length() < MIN_QUERY_LENGTH || folded.length() > MAX_QUERY_LENGTH) {
			throw new InvalidRequestException(
					"Type between " + MIN_QUERY_LENGTH + " and " + MAX_QUERY_LENGTH + " characters to search.");
		}
		List<String> words = NameSearch.words(folded);
		Map<UUID, Organization> teams = programTeams(programId);
		if (teams.isEmpty()) {
			return new ProgramTeamPeopleResponse(List.of(), false);
		}
		Map<UUID, ProgramTeamPeopleResponse.Person> found = new LinkedHashMap<>();
		for (Organization team : teams.values()) {
			AppUser founder = team.getFounder();
			if (founder != null && NameSearch.matches(founder.getFullName(), words)) {
				found.putIfAbsent(founder.getId(), person(founder, TeamPersonRole.FOUNDER, team));
			}
		}
		// Members of any account status, as the team's drawer lists them.
		for (AppUser member : appUserRepository.findByOrganizationIdInOrderByFullNameAsc(teams.keySet())) {
			Organization team = member.getOrganization() == null ? null : teams.get(member.getOrganization().getId());
			if (team != null && NameSearch.matches(member.getFullName(), words)) {
				found.putIfAbsent(member.getId(), person(member, TeamPersonRole.MEMBER, team));
			}
		}
		List<ProgramTeamPeopleResponse.Person> people = found.values().stream()
				.sorted(Comparator.comparing(ProgramTeamPeopleResponse.Person::fullName, TURKISH)
						.thenComparing(ProgramTeamPeopleResponse.Person::teamName, TURKISH))
				.toList();
		return new ProgramTeamPeopleResponse(
				people.stream().limit(MAX_RESULTS).toList(), people.size() > MAX_RESULTS);
	}

	/** The joined teams Takımlar lists: the program's startups whose organization may take part. */
	private Map<UUID, Organization> programTeams(UUID programId) {
		List<UUID> organizationIds = programStartupRepository.findByProgramIdIn(List.of(programId)).stream()
				.map(ProgramStartup::getOrganizationId)
				.distinct()
				.toList();
		return organizationRepository.findAllById(organizationIds).stream()
				.filter(Organization::isApprovedForProgram)
				.collect(Collectors.toMap(Organization::getId, Function.identity()));
	}

	private static ProgramTeamPeopleResponse.Person person(AppUser user, TeamPersonRole role, Organization team) {
		return new ProgramTeamPeopleResponse.Person(
				user.getId(), user.getFullName(), role, user.getJobTitle(), team.getId(), team.getName());
	}
}
