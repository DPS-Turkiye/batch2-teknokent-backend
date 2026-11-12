package com.dps.teknokent.service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.RegistrationProperties;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.Texts;

/**
 * Limits new accounts to the deployment's e-mail domains, so a university deployment only takes its own addresses.
 * A department subdomain counts as the university's (ceng.metu.edu.tr under metu.edu.tr).
 */
@Component
@EnableConfigurationProperties(RegistrationProperties.class)
public class EmailDomainPolicy {

	private final List<String> allowedDomains;

	public EmailDomainPolicy(RegistrationProperties properties) {
		allowedDomains = properties.allowedEmailDomains().stream()
				.map(domain -> domain.trim().toLowerCase(Locale.ROOT))
				.map(domain -> domain.startsWith("@") ? domain.substring(1) : domain)
				.filter(domain -> !domain.isEmpty())
				.distinct()
				.toList();
	}

	public void requireAllowed(String email) {
		if (!allows(email)) {
			String domains = allowedDomains.stream().map(domain -> "@" + domain).collect(Collectors.joining(", "));
			throw new InvalidRequestException(Texts.forRequest("error.emailDomainNotAllowed", domains));
		}
	}

	boolean allows(String email) {
		if (allowedDomains.isEmpty()) {
			return true;
		}
		if (email == null) {
			return false;
		}
		int at = email.lastIndexOf('@');
		if (at < 0) {
			return false;
		}
		String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
		return allowedDomains.stream().anyMatch(allowed -> domain.equals(allowed) || domain.endsWith("." + allowed));
	}
}
