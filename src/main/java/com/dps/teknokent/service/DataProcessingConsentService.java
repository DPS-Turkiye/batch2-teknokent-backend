package com.dps.teknokent.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.ConsentAnswer;
import com.dps.teknokent.domain.ProcessingConsentPurpose;
import com.dps.teknokent.domain.ProcessingConsentSource;
import com.dps.teknokent.entity.DataProcessingConsent;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.DataProcessingConsentRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;

/** Keeps a record of each data-processing consent, naming the signed-in person who gave it. */
@Service
public class DataProcessingConsentService {

	private final DataProcessingConsentRepository repository;
	private final Clock clock;

	@Autowired
	public DataProcessingConsentService(DataProcessingConsentRepository repository) {
		this(repository, Clock.systemUTC());
	}

	DataProcessingConsentService(DataProcessingConsentRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * Records a CV-parsing consent just before the CV is processed. {@code subjectUserId} is the person the CV is
	 * about when that is not the caller (a founder filing a team member's profile); null means the caller.
	 *
	 * <p>Only a person signed in with Firebase can consent. The shared service credentials stand for nobody, so a
	 * consent arriving on them is refused rather than stored without a name.
	 */
	public DataProcessingConsent record(ConsentAnswer answer, ProcessingConsentSource source, UUID subjectUserId) {
		answer.requireAccepted();
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object principal = authentication == null ? null : authentication.getPrincipal();
		UUID consenterUserId;
		String consenterFirebaseUid;
		if (principal instanceof AppUserPrincipal user) {
			// The account names the person; the uid is kept only for someone who has no account yet.
			consenterUserId = user.id();
			consenterFirebaseUid = null;
		} else if (principal instanceof FirebaseIdentity identity) {
			consenterUserId = null;
			consenterFirebaseUid = identity.uid();
		} else {
			throw new InvalidRequestException("Consent can only be given by the signed-in person it is about.");
		}
		return repository.save(DataProcessingConsent.of(
				consenterUserId,
				consenterFirebaseUid,
				subjectUserId == null ? consenterUserId : subjectUserId,
				ProcessingConsentPurpose.CV_PARSING,
				source,
				answer.textVersion(),
				clock.instant()));
	}
}
