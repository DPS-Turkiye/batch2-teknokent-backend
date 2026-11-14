package com.dps.teknokent.service;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;

/**
 * Who a Menton account is in office hours: what ODTÜ's record says they are, and their office-hours person, made on
 * first use. Shared by the academician and student sides of {@code /api/office-hours/me}.
 */
@Component
public class OfficeHoursPeople {

	private final InstitutionPersonRepository institutionPeople;
	private final OfficeHoursUserRepository userRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursPeople(InstitutionPersonRepository institutionPeople, OfficeHoursUserRepository userRepository) {
		this(institutionPeople, userRepository, Clock.systemUTC());
	}

	OfficeHoursPeople(InstitutionPersonRepository institutionPeople, OfficeHoursUserRepository userRepository,
			Clock clock) {
		this.institutionPeople = institutionPeople;
		this.userRepository = userRepository;
		this.clock = clock;
	}

	/** ODTÜ's record for the account, which must say they are {@code kind}; otherwise there is nothing here. */
	public InstitutionPerson require(UUID accountId, IdentityKind kind) {
		return institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(accountId)
				.filter(InstitutionPerson::isActive)
				.filter(record -> record.kinds().contains(kind))
				.orElseThrow(() -> new ResourceNotFoundException("Office hours not found."));
	}

	public Optional<OfficeHoursUser> existing(UUID accountId) {
		return userRepository.findByAppUserId(accountId);
	}

	/** The account's office-hours person, made on first use under the name ODTÜ's record gives. */
	public OfficeHoursUser personFor(UUID accountId, InstitutionPerson record) {
		Optional<OfficeHoursUser> found = existing(accountId);
		if (found.isEmpty()) {
			userRepository.insertForAccountIfAbsent(UUID.randomUUID(), accountId, record.getFullName(),
					clock.instant());
			found = existing(accountId);
		}
		OfficeHoursUser person = found.orElseThrow(() -> new IllegalStateException("Office-hours person missing"));
		if (!Objects.equals(person.getFullName(), record.getFullName())) {
			person.setFullName(record.getFullName());
			userRepository.save(person);
		}
		return person;
	}

	/** Each account's latest ODTÜ record, in one query; accounts without one are left out. */
	public Map<UUID, InstitutionPerson> recordsOf(Collection<UUID> accountIds) {
		List<UUID> ids = accountIds.stream().filter(Objects::nonNull).distinct().toList();
		return ids.isEmpty() ? Map.of()
				: institutionPeople.findByUserIdIn(ids).stream()
						.collect(Collectors.toMap(record -> record.getUser().getId(), Function.identity(),
								BinaryOperator.maxBy(Comparator.comparing(InstitutionPerson::getSyncedAt))));
	}
}
