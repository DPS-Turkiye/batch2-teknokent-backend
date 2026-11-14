package com.dps.teknokent.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.requests.MentorAvailabilityExceptionRequest;
import com.dps.teknokent.exception.InvalidRequestException;

/** Checks on a weekly availability and its days off, shared by mentors and office-hours instructors. */
final class AvailabilityRules {

	private AvailabilityRules() {
	}

	static void validateRules(List<CreateMentorAvailabilityRuleRequest> rules) {
		Set<String> seen = new HashSet<>();
		for (CreateMentorAvailabilityRuleRequest rule : rules) {
			if (!rule.endTime().isAfter(rule.startTime())) {
				throw new InvalidRequestException("Availability must end after it starts");
			}
			if (!seen.add(rule.dayOfWeek() + ":" + rule.startTime())) {
				throw new InvalidRequestException("Duplicate weekly availability rule");
			}
		}
		for (int first = 0; first < rules.size(); first++) {
			for (int second = first + 1; second < rules.size(); second++) {
				CreateMentorAvailabilityRuleRequest left = rules.get(first);
				CreateMentorAvailabilityRuleRequest right = rules.get(second);
				if (left.dayOfWeek() == right.dayOfWeek() && left.startTime().isBefore(right.endTime())
						&& right.startTime().isBefore(left.endTime())) {
					throw new InvalidRequestException("Weekly availability ranges cannot overlap");
				}
			}
		}
	}

	static void validateExceptions(List<MentorAvailabilityExceptionRequest> exceptions) {
		Set<String> seen = new HashSet<>();
		for (MentorAvailabilityExceptionRequest exception : exceptions) {
			if (exception.endsOn().isBefore(exception.startsOn())) {
				throw new InvalidRequestException("Availability exception must end on or after it starts");
			}
			// The tables are unique on these dates, so a repeat would otherwise surface as a 500.
			if (!seen.add(exception.startsOn() + ":" + exception.endsOn())) {
				throw new InvalidRequestException("Duplicate availability exception dates");
			}
		}
	}
}
