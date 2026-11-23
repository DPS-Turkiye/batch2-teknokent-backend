package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailExclusion;
import com.dps.teknokent.domain.EmailRecipientKind;
import com.dps.teknokent.domain.EmailSuppressionReason;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.util.EmailDomains;

import lombok.RequiredArgsConstructor;

/**
 * Who is on a program's audience right now. Only people whose address the program's coordinator already sees: the
 * teams in Takımlar, the accepted mentors, and the people those teams' founders listed. Callers check the program.
 */
@Component
@RequiredArgsConstructor
class EmailAudienceResolver {

	private final ProgramStartupRepository programStartupRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final OrganizationRepository organizationRepository;
	private final OrganizationPendingMemberRepository pendingMemberRepository;
	private final AppUserRepository appUserRepository;
	private final EmailSuppressionService suppressionService;
	private final EmailCenterSettings settings;

	/** {@code exclusion} is null for a person the mail would go to. */
	record Candidate(
			@Nullable UUID userId,
			@Nullable UUID organizationId,
			@Nullable UUID pendingMemberId,
			EmailRecipientKind kind,
			@Nullable String address,
			String fullName,
			@Nullable String teamName,
			@Nullable UserAccountStatus accountStatus,
			@Nullable EmailExclusion exclusion) {
	}

	/** One row per address, by name; a person who is both participant and mentor is listed once. */
	List<Candidate> resolve(UUID programId, EmailAudience audience) {
		List<Candidate> found = new ArrayList<>();
		switch (audience) {
		case PARTICIPANTS -> found.addAll(participants(programId));
		case MENTORS -> found.addAll(mentors(programId));
		case EVERYONE -> {
			found.addAll(participants(programId));
			found.addAll(mentors(programId));
		}
		case UNJOINED -> found.addAll(unjoined(programId));
		}
		Map<String, Candidate> byAddress = new LinkedHashMap<>();
		List<Candidate> rows = new ArrayList<>();
		for (Candidate candidate : found) {
			if (candidate.address() == null) {
				rows.add(candidate);
			} else {
				byAddress.putIfAbsent(candidate.address(), candidate);
			}
		}
		Map<String, EmailSuppressionReason> suppressed = suppressionService.reasonsFor(byAddress.keySet());
		byAddress.values().forEach(candidate -> rows.add(withDeliveryLimits(candidate, suppressed)));
		rows.sort(Comparator.comparing((Candidate candidate) -> candidate.fullName().toLowerCase(Locale.ROOT))
				.thenComparing(candidate -> candidate.address() == null ? "" : candidate.address()));
		return rows;
	}

	private List<Candidate> participants(UUID programId) {
		Map<UUID, Organization> teams = teamsOf(programId);
		if (teams.isEmpty()) {
			return List.of();
		}
		Map<UUID, Candidate> found = new LinkedHashMap<>();
		for (AppUser member : appUserRepository.findByOrganizationIdInOrderByFullNameAsc(teams.keySet())) {
			Organization team = member.getOrganization() == null ? null : teams.get(member.getOrganization().getId());
			if (team != null) {
				found.put(member.getId(), account(member, team, EmailRecipientKind.PARTICIPANT));
			}
		}
		for (Organization team : teams.values()) {
			AppUser founder = team.getFounder();
			if (founder != null) {
				found.putIfAbsent(founder.getId(), account(founder, team, EmailRecipientKind.PARTICIPANT));
			}
		}
		return List.copyOf(found.values());
	}

	private List<Candidate> mentors(UUID programId) {
		List<UUID> ids = programMentorRepository.findByProgramIdOrderByCreatedAtAsc(programId).stream()
				.filter(membership -> membership.getStatus() == ProgramMentorStatus.ACCEPTED)
				.map(ProgramMentor::getMentorId)
				.toList();
		return appUserRepository.findAllById(ids).stream()
				.filter(mentor -> mentor.getStatus() != UserAccountStatus.DELETED)
				.map(mentor -> account(mentor, null, EmailRecipientKind.MENTOR))
				.toList();
	}

	private List<Candidate> unjoined(UUID programId) {
		Map<UUID, Organization> teams = teamsOf(programId);
		if (teams.isEmpty()) {
			return List.of();
		}
		List<OrganizationPendingMember> listed = pendingMemberRepository
				.findByOrganizationIdInOrderByCreatedAtAsc(teams.keySet());
		Set<String> registered = registered(listed);
		List<Candidate> found = new ArrayList<>();
		for (OrganizationPendingMember person : listed) {
			Organization team = teams.get(person.getOrganizationId());
			String address = EmailDomains.normalizeEmail(person.getEmail());
			boolean usable = EmailDomains.isEmail(address);
			EmailExclusion exclusion = null;
			if (!usable) {
				exclusion = EmailExclusion.NO_ADDRESS;
			} else if (registered.contains(address)) {
				exclusion = EmailExclusion.NOT_ELIGIBLE;
			}
			found.add(new Candidate(null, person.getOrganizationId(), person.getId(), EmailRecipientKind.UNJOINED,
					usable ? address : null, person.getFullName(), team == null ? null : team.getName(), null,
					exclusion));
		}
		return found;
	}

	private Set<String> registered(Collection<OrganizationPendingMember> listed) {
		Set<String> addresses = listed.stream()
				.map(person -> EmailDomains.normalizeEmail(person.getEmail()))
				.filter(address -> !address.isEmpty())
				.collect(Collectors.toSet());
		return addresses.isEmpty() ? Set.of() : new HashSet<>(appUserRepository.findRegisteredEmails(addresses));
	}

	/** The teams Takımlar lists for the program. */
	private Map<UUID, Organization> teamsOf(UUID programId) {
		List<UUID> ids = programStartupRepository.findByProgramIdIn(List.of(programId)).stream()
				.map(ProgramStartup::getOrganizationId)
				.distinct()
				.toList();
		return organizationRepository.findAllById(ids).stream()
				.filter(Organization::isApprovedForProgram)
				.collect(Collectors.toMap(Organization::getId, Function.identity()));
	}

	private static Candidate account(AppUser user, @Nullable Organization team, EmailRecipientKind kind) {
		String address = EmailDomains.normalizeEmail(user.getEmail());
		return new Candidate(
				user.getId(),
				team == null ? null : team.getId(),
				null,
				kind,
				address,
				user.getFullName(),
				team == null ? null : Organization.visibleName(team),
				user.getStatus(),
				user.getStatus() == UserAccountStatus.APPROVED ? null : EmailExclusion.ACCOUNT_NOT_APPROVED);
	}

	private Candidate withDeliveryLimits(Candidate candidate, Map<String, EmailSuppressionReason> suppressed) {
		String address = candidate.address();
		if (candidate.exclusion() != null || address == null) {
			return candidate;
		}
		EmailExclusion exclusion = null;
		EmailSuppressionReason reason = suppressed.get(address);
		if (reason == EmailSuppressionReason.UNSUBSCRIBED) {
			exclusion = EmailExclusion.UNSUBSCRIBED;
		} else if (reason != null) {
			exclusion = EmailExclusion.UNDELIVERABLE;
		} else if (!settings.mayWriteTo(address)) {
			exclusion = EmailExclusion.NOT_ON_ALLOWLIST;
		}
		return new Candidate(candidate.userId(), candidate.organizationId(), candidate.pendingMemberId(),
				candidate.kind(), address, candidate.fullName(), candidate.teamName(), candidate.accountStatus(),
				exclusion);
	}
}
